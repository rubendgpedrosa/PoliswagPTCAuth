package main

import (
	"bytes"
	"encoding/json"
	"io"
	"log"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/gorilla/websocket"
)

const testJobURL = "https://access.pokemon.com/oauth2/auth?client_id=x"

func newTestServer(timeout time.Duration, authSecret string) (*httptest.Server, *StatsStore) {
	stats := NewStatsStore("")
	app := NewApp(NewJobQueue(timeout), authSecret, stats, log.New(io.Discard, "", 0))
	return httptest.NewServer(app.Handler()), stats
}

func wsURL(baseURL string) string {
	return "ws" + strings.TrimPrefix(baseURL, "http") + "/ws"
}

func postLogin(t *testing.T, baseURL string, body any, headers map[string]string) (*http.Response, map[string]any) {
	t.Helper()
	data, _ := json.Marshal(body)
	req, err := http.NewRequest(http.MethodPost, baseURL+"/api/v1/login-code", bytes.NewReader(data))
	if err != nil {
		t.Fatal(err)
	}
	req.Header.Set("content-type", "application/json")
	for k, v := range headers {
		req.Header.Set(k, v)
	}
	res, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer res.Body.Close()
	var decoded map[string]any
	if err := json.NewDecoder(res.Body).Decode(&decoded); err != nil {
		t.Fatal(err)
	}
	return res, decoded
}

func stubExtension(t *testing.T, url string, reply func(map[string]any) map[string]any) *websocket.Conn {
	t.Helper()
	conn, _, err := websocket.DefaultDialer.Dial(wsURL(url), nil)
	if err != nil {
		t.Fatal(err)
	}
	go func() {
		for {
			var msg map[string]any
			if err := conn.ReadJSON(&msg); err != nil {
				return
			}
			if msg["type"] == "job" {
				out := reply(msg)
				out["type"] = "result"
				out["id"] = msg["id"]
				_ = conn.WriteJSON(out)
			}
		}
	}()
	return conn
}

func openExtension(t *testing.T, baseURL string) *websocket.Conn {
	t.Helper()
	conn, _, err := websocket.DefaultDialer.Dial(wsURL(baseURL), nil)
	if err != nil {
		t.Fatal(err)
	}
	return conn
}

func TestLoginSuccess(t *testing.T) {
	ts, stats := newTestServer(2*time.Second, "")
	defer ts.Close()
	ext := stubExtension(t, ts.URL, func(job map[string]any) map[string]any {
		return map[string]any{"status": "SUCCESS", "login_code": "code-for-" + job["username"].(string)}
	})
	defer ext.Close()

	res, body := postLogin(t, ts.URL, JobPayload{Username: "ash", Password: "pw", URL: testJobURL}, nil)
	if res.StatusCode != 200 || body["status"] != "SUCCESS" || body["login_code"] != "code-for-ash" {
		t.Fatalf("unexpected response: %d %+v", res.StatusCode, body)
	}
	if snap := stats.Snapshot(); snap.Total != 1 || snap.Success != 1 {
		t.Fatalf("unexpected stats: %+v", snap)
	}
}

func TestLoginBannedMalformedTimeoutCancelAndAuth(t *testing.T) {
	t.Run("banned", func(t *testing.T) {
		ts, _ := newTestServer(2*time.Second, "")
		defer ts.Close()
		ext := stubExtension(t, ts.URL, func(map[string]any) map[string]any {
			return map[string]any{"status": "BANNED"}
		})
		defer ext.Close()
		res, body := postLogin(t, ts.URL, JobPayload{Username: "a", Password: "b", URL: testJobURL}, nil)
		if res.StatusCode != 418 || body["status"] != "BANNED" {
			t.Fatalf("unexpected response: %d %+v", res.StatusCode, body)
		}
	})

	t.Run("malformed", func(t *testing.T) {
		ts, _ := newTestServer(2*time.Second, "")
		defer ts.Close()
		res, body := postLogin(t, ts.URL, map[string]string{"username": "only-user"}, nil)
		if res.StatusCode != 400 || body["status"] != "ERROR" {
			t.Fatalf("unexpected response: %d %+v", res.StatusCode, body)
		}
	})

	t.Run("timeout and cancel", func(t *testing.T) {
		ts, _ := newTestServer(30*time.Millisecond, "")
		defer ts.Close()
		ext := openExtension(t, ts.URL)
		defer ext.Close()
		cancelCh := make(chan map[string]any, 1)
		go func() {
			for {
				var msg map[string]any
				if err := ext.ReadJSON(&msg); err != nil {
					return
				}
				if msg["type"] == "cancel" {
					cancelCh <- msg
					return
				}
			}
		}()

		res, body := postLogin(t, ts.URL, JobPayload{Username: "a", Password: "b", URL: testJobURL}, nil)
		if res.StatusCode != 408 || body["status"] != "TIMEOUT" {
			t.Fatalf("unexpected response: %d %+v", res.StatusCode, body)
		}
		select {
		case msg := <-cancelCh:
			if msg["status"] != "TIMEOUT" || msg["id"] == "" {
				t.Fatalf("unexpected cancel: %+v", msg)
			}
		case <-time.After(time.Second):
			t.Fatal("timed out waiting for cancel")
		}
	})

	t.Run("auth", func(t *testing.T) {
		ts, _ := newTestServer(2*time.Second, "s3cret")
		defer ts.Close()
		res, _ := postLogin(t, ts.URL, JobPayload{Username: "a", Password: "b", URL: testJobURL}, map[string]string{"x-auth-secret": "wrong"})
		if res.StatusCode != 401 {
			t.Fatalf("unexpected status: %d", res.StatusCode)
		}
	})
}

func TestHealthAndReplacement(t *testing.T) {
	ts, _ := newTestServer(2*time.Second, "")
	defer ts.Close()

	res, err := http.Get(ts.URL + "/health")
	if err != nil {
		t.Fatal(err)
	}
	var before map[string]any
	_ = json.NewDecoder(res.Body).Decode(&before)
	_ = res.Body.Close()
	if before["status"] != "OK" || before["extension_connected"] != false {
		t.Fatalf("unexpected health before: %+v", before)
	}

	first := openExtension(t, ts.URL)
	second := openExtension(t, ts.URL)
	defer second.Close()

	_, _, err = first.ReadMessage()
	if !websocket.IsCloseError(err, closeReplaced) {
		t.Fatalf("expected first connection to close with %d, got %v", closeReplaced, err)
	}
}

func dialWS(baseURL, origin string) (*websocket.Conn, *http.Response, error) {
	h := http.Header{}
	if origin != "" {
		h.Set("Origin", origin)
	}
	return websocket.DefaultDialer.Dial(wsURL(baseURL), h)
}

func TestWSOriginCheck(t *testing.T) {
	ts, _ := newTestServer(2*time.Second, "s3cret")
	defer ts.Close()
	for _, origin := range []string{"https://evil.example", "null", "http://127.0.0.1:5090"} {
		if conn, res, err := dialWS(ts.URL, origin); err == nil {
			conn.Close()
			t.Fatalf("origin %q was allowed", origin)
		} else if res == nil || res.StatusCode != http.StatusForbidden {
			t.Fatalf("origin %q: expected 403, got %v %v", origin, res, err)
		}
	}
	for _, origin := range []string{"chrome-extension://abcdef", "moz-extension://1234-uuid"} {
		conn, _, err := dialWS(ts.URL, origin)
		if err != nil {
			t.Fatalf("origin %q rejected: %v", origin, err)
		}
		conn.Close()
	}
}

func TestLoginRejectsBrowserRequestsAndBadURLs(t *testing.T) {
	ts, _ := newTestServer(2*time.Second, "")
	defer ts.Close()

	res, _ := postLogin(t, ts.URL, JobPayload{Username: "a", Password: "b", URL: testJobURL}, map[string]string{"Origin": "https://evil.example", "content-type": "text/plain"})
	if res.StatusCode != http.StatusForbidden {
		t.Fatalf("browser POST: expected 403, got %d", res.StatusCode)
	}
	for _, u := range []string{"http://access.pokemon.com/x", "https://evil.example/", "https://access.pokemon.com.evil.example/", "javascript:alert(1)", "https://u:p@access.pokemon.com/"} {
		res, _ := postLogin(t, ts.URL, JobPayload{Username: "a", Password: "b", URL: u}, nil)
		if res.StatusCode != http.StatusBadRequest {
			t.Fatalf("url %q: expected 400, got %d", u, res.StatusCode)
		}
	}
}

func TestCallerCancelSendsCancel(t *testing.T) {
	ts, stats := newTestServer(5*time.Second, "")
	defer ts.Close()
	ext := openExtension(t, ts.URL)
	defer ext.Close()
	msgs := make(chan map[string]any, 4)
	go func() {
		for {
			var msg map[string]any
			if err := ext.ReadJSON(&msg); err != nil {
				return
			}
			msgs <- msg
		}
	}()

	data, _ := json.Marshal(JobPayload{Username: "a", Password: "b", URL: testJobURL})
	client := &http.Client{Timeout: 200 * time.Millisecond}
	_, err := client.Post(ts.URL+"/api/v1/login-code", "application/json", bytes.NewReader(data))
	if err == nil {
		t.Fatal("expected client timeout")
	}
	deadline := time.After(2 * time.Second)
	for {
		select {
		case msg := <-msgs:
			if msg["type"] == "cancel" {
				if msg["status"] != "CANCELLED" {
					t.Fatalf("unexpected cancel: %+v", msg)
				}
				waitFor(t, func() bool { return stats.Snapshot().Failed == 1 })
				return
			}
		case <-deadline:
			t.Fatal("no cancel after caller went away")
		}
	}
}

func TestBacklogDrainsOnConnect(t *testing.T) {
	ts, _ := newTestServer(2*time.Second, "")
	defer ts.Close()
	done := make(chan int, 3)
	for i := 0; i < 3; i++ {
		go func() {
			res, _ := postLogin(t, ts.URL, JobPayload{Username: "a", Password: "b", URL: testJobURL}, nil)
			done <- res.StatusCode
		}()
	}
	waitFor(t, func() bool { return healthPending(t, ts.URL) == 3 })
	ext := stubExtension(t, ts.URL, func(map[string]any) map[string]any {
		return map[string]any{"status": "SUCCESS", "login_code": "c"}
	})
	defer ext.Close()
	for i := 0; i < 3; i++ {
		select {
		case code := <-done:
			if code != 200 {
				t.Fatalf("unexpected status %d", code)
			}
		case <-time.After(time.Second):
			t.Fatal("backlog not drained after connect")
		}
	}
}

func TestDroppedConnectionJobsExpireAfterGrace(t *testing.T) {
	app := NewApp(NewJobQueue(5*time.Second), "", nil, log.New(io.Discard, "", 0))
	app.grace = 50 * time.Millisecond
	ts := httptest.NewServer(app.Handler())
	defer ts.Close()
	ext := openExtension(t, ts.URL)
	got := make(chan struct{})
	go func() {
		var msg map[string]any
		if ext.ReadJSON(&msg) == nil && msg["type"] == "job" {
			close(got)
			ext.Close() // tab state lost, no result will come
		}
	}()
	start := time.Now()
	res, body := postLogin(t, ts.URL, JobPayload{Username: "a", Password: "b", URL: testJobURL}, nil)
	<-got
	if res.StatusCode != 500 || body["status"] != "ERROR" {
		t.Fatalf("unexpected response: %d %+v", res.StatusCode, body)
	}
	if time.Since(start) > 2*time.Second {
		t.Fatal("job waited for full timeout instead of grace")
	}
}

func TestResultAcceptedAfterReconnect(t *testing.T) {
	ts, _ := newTestServer(5*time.Second, "")
	defer ts.Close()
	first := openExtension(t, ts.URL)
	ids := make(chan string, 1)
	go func() {
		var msg map[string]any
		if first.ReadJSON(&msg) == nil {
			ids <- msg["id"].(string)
			first.Close()
		}
	}()
	done := make(chan map[string]any, 1)
	go func() {
		_, body := postLogin(t, ts.URL, JobPayload{Username: "a", Password: "b", URL: testJobURL}, nil)
		done <- body
	}()
	id := <-ids
	waitFor(t, func() bool { return !healthConnected(t, ts.URL) })
	second := openExtension(t, ts.URL)
	defer second.Close()
	_ = second.WriteJSON(map[string]string{"type": "result", "id": id, "status": "SUCCESS", "login_code": "held"})
	select {
	case body := <-done:
		if body["status"] != "SUCCESS" || body["login_code"] != "held" {
			t.Fatalf("unexpected body: %+v", body)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("held result not accepted")
	}
}

func health(t *testing.T, baseURL string) map[string]any {
	t.Helper()
	res, err := http.Get(baseURL + "/health")
	if err != nil {
		t.Fatal(err)
	}
	defer res.Body.Close()
	var h map[string]any
	_ = json.NewDecoder(res.Body).Decode(&h)
	return h
}

func healthPending(t *testing.T, baseURL string) int {
	n, _ := health(t, baseURL)["pending_jobs"].(float64)
	return int(n)
}

func healthConnected(t *testing.T, baseURL string) bool {
	v, _ := health(t, baseURL)["extension_connected"].(bool)
	return v
}

func waitFor(t *testing.T, cond func() bool) {
	t.Helper()
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		if cond() {
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatal("condition not met in time")
}

func newWorkerTestServer(opts AppOptions) *httptest.Server {
	app := NewApp(NewJobQueue(2*time.Second), "", nil, log.New(io.Discard, "", 0), opts)
	return httptest.NewServer(app.Handler())
}

func dialWorker(baseURL, query string, header http.Header) (*websocket.Conn, int, error) {
	conn, res, err := websocket.DefaultDialer.Dial(wsURL(baseURL)+query, header)
	code := 0
	if res != nil {
		code = res.StatusCode
	}
	return conn, code, err
}

func TestWorkerTokenAuth(t *testing.T) {
	ts := newWorkerTestServer(AppOptions{WorkerToken: "tok", NoLocalTrust: true})
	defer ts.Close()

	cases := []struct {
		name   string
		query  string
		header http.Header
		ok     bool
	}{
		{"no token", "?worker=a", nil, false},
		{"wrong token", "?worker=a&token=nope", nil, false},
		{"query token", "?worker=a&token=tok", nil, true},
		{"bearer token", "?worker=b", http.Header{"Authorization": {"Bearer tok"}}, true},
		{"extension origin + token", "?worker=c&token=tok", http.Header{"Origin": {"moz-extension://x"}}, true},
		{"web origin + token", "?worker=d&token=tok", http.Header{"Origin": {"https://evil.example"}}, false},
		{"bad worker id", "?worker=a%20b&token=tok", nil, false},
	}
	for _, c := range cases {
		conn, code, err := dialWorker(ts.URL, c.query, c.header)
		if c.ok != (err == nil) {
			t.Fatalf("%s: ok=%v but err=%v code=%d", c.name, c.ok, err, code)
		}
		if conn != nil {
			conn.Close()
		}
	}
}

func TestForwardedLoopbackNeedsToken(t *testing.T) {
	ts := newWorkerTestServer(AppOptions{})
	defer ts.Close()
	if _, code, err := dialWorker(ts.URL, "", http.Header{"X-Forwarded-For": {"203.0.113.9"}}); err == nil || code != http.StatusForbidden {
		t.Fatalf("forwarded connection should need a token, got code %d err %v", code, err)
	}
	conn, _, err := dialWorker(ts.URL, "", nil)
	if err != nil {
		t.Fatalf("plain local connection rejected: %v", err)
	}
	conn.Close()
}

func TestJobsSpreadAcrossWorkersAndIDsCoexist(t *testing.T) {
	ts := newWorkerTestServer(AppOptions{})
	defer ts.Close()

	got := make(chan string, 4)
	for _, id := range []string{"phone", "browser"} {
		conn, _, err := dialWorker(ts.URL, "?worker="+id, nil)
		if err != nil {
			t.Fatal(err)
		}
		defer conn.Close()
		go func(id string, conn *websocket.Conn) {
			for {
				var msg map[string]any
				if conn.ReadJSON(&msg) != nil {
					return
				}
				if msg["type"] == "job" {
					got <- id
				}
			}
		}(id, conn)
	}
	waitFor(t, func() bool { n, _ := health(t, ts.URL)["workers"].(float64); return n == 2 })

	for i := 0; i < 2; i++ {
		go func() {
			data, _ := json.Marshal(JobPayload{Username: "a", Password: "b", URL: testJobURL})
			if res, err := http.Post(ts.URL+"/api/v1/login-code", "application/json", bytes.NewReader(data)); err == nil {
				res.Body.Close()
			}
		}()
	}
	seen := map[string]int{}
	for i := 0; i < 2; i++ {
		select {
		case id := <-got:
			seen[id]++
		case <-time.After(time.Second):
			t.Fatal("job not dispatched")
		}
	}
	if seen["phone"] != 1 || seen["browser"] != 1 {
		t.Fatalf("jobs not spread across workers: %v", seen)
	}
}

func TestSameWorkerIDIsReplaced(t *testing.T) {
	ts := newWorkerTestServer(AppOptions{})
	defer ts.Close()
	first, _, err := dialWorker(ts.URL, "?worker=phone", nil)
	if err != nil {
		t.Fatal(err)
	}
	second, _, err := dialWorker(ts.URL, "?worker=phone", nil)
	if err != nil {
		t.Fatal(err)
	}
	defer second.Close()
	if _, _, err := first.ReadMessage(); !websocket.IsCloseError(err, closeReplaced) {
		t.Fatalf("expected close %d, got %v", closeReplaced, err)
	}
}

func TestHealthCORSOnlyForExtensions(t *testing.T) {
	ts := newWorkerTestServer(AppOptions{})
	defer ts.Close()
	for origin, want := range map[string]string{
		"chrome-extension://abc": "chrome-extension://abc",
		"https://evil.example":   "",
	} {
		req, _ := http.NewRequest(http.MethodGet, ts.URL+"/health", nil)
		req.Header.Set("Origin", origin)
		res, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		res.Body.Close()
		if got := res.Header.Get("Access-Control-Allow-Origin"); got != want {
			t.Fatalf("origin %q: ACAO %q, want %q", origin, got, want)
		}
	}
}

func TestResultFromOtherWorkerIgnored(t *testing.T) {
	ts := newWorkerTestServer(AppOptions{})
	defer ts.Close()
	owner, _, err := dialWorker(ts.URL, "?worker=a", nil)
	if err != nil {
		t.Fatal(err)
	}
	defer owner.Close()
	done := make(chan map[string]any, 1)
	go func() {
		res, err := http.Post(ts.URL+"/api/v1/login-code", "application/json",
			strings.NewReader(`{"username":"a","password":"b","url":"`+testJobURL+`"}`))
		if err != nil {
			done <- nil
			return
		}
		defer res.Body.Close()
		var body map[string]any
		_ = json.NewDecoder(res.Body).Decode(&body)
		done <- body
	}()
	var job map[string]any
	if err := owner.ReadJSON(&job); err != nil {
		t.Fatal(err)
	}
	other, _, err := dialWorker(ts.URL, "?worker=b", nil)
	if err != nil {
		t.Fatal(err)
	}
	defer other.Close()
	_ = other.WriteJSON(map[string]any{"type": "result", "id": job["id"], "status": "BANNED"})
	time.Sleep(100 * time.Millisecond)
	if healthPending(t, ts.URL) != 1 {
		t.Fatal("another worker settled a job it did not own")
	}
	_ = owner.WriteJSON(map[string]any{"type": "result", "id": job["id"], "status": "SUCCESS", "login_code": "c"})
	if body := <-done; body == nil || body["status"] != "SUCCESS" {
		t.Fatalf("unexpected: %+v", body)
	}
}

func TestGraceExpiryCancelsOnReconnectedWorker(t *testing.T) {
	app := NewApp(NewJobQueue(5*time.Second), "", nil, log.New(io.Discard, "", 0))
	app.grace = 200 * time.Millisecond
	ts := httptest.NewServer(app.Handler())
	defer ts.Close()
	first, _, err := dialWorker(ts.URL, "?worker=a", nil)
	if err != nil {
		t.Fatal(err)
	}
	go func() {
		res, err := http.Post(ts.URL+"/api/v1/login-code", "application/json",
			strings.NewReader(`{"username":"a","password":"b","url":"`+testJobURL+`"}`))
		if err == nil {
			res.Body.Close()
		}
	}()
	var job map[string]any
	if err := first.ReadJSON(&job); err != nil {
		t.Fatal(err)
	}
	first.Close()
	waitFor(t, func() bool { return !healthConnected(t, ts.URL) })
	second, _, err := dialWorker(ts.URL, "?worker=a", nil)
	if err != nil {
		t.Fatal(err)
	}
	defer second.Close()
	_ = second.SetReadDeadline(time.Now().Add(3 * time.Second))
	var msg map[string]any
	if err := second.ReadJSON(&msg); err != nil {
		t.Fatalf("no cancel after grace: %v", err)
	}
	if msg["type"] != "cancel" || msg["id"] != job["id"] || msg["status"] != "ERROR" {
		t.Fatalf("unexpected message: %+v", msg)
	}
}

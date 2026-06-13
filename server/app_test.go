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

	res, body := postLogin(t, ts.URL, JobPayload{Username: "ash", Password: "pw", URL: "http://oauth"}, nil)
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
		res, body := postLogin(t, ts.URL, JobPayload{Username: "a", Password: "b", URL: "http://oauth"}, nil)
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

		res, body := postLogin(t, ts.URL, JobPayload{Username: "a", Password: "b", URL: "http://oauth"}, nil)
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
		res, _ := postLogin(t, ts.URL, JobPayload{Username: "a", Password: "b", URL: "http://oauth"}, map[string]string{"x-auth-secret": "wrong"})
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
	if !websocket.IsCloseError(err, websocket.CloseNormalClosure) {
		t.Fatalf("expected first connection to close normally, got %v", err)
	}
}

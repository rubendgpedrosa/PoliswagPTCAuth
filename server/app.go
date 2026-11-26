package main

import (
	"crypto/subtle"
	"encoding/json"
	"io"
	"log"
	"net"
	"net/http"
	"net/url"
	"regexp"
	"strings"
	"sync"
	"time"

	"github.com/gorilla/websocket"
)

const maxBodyBytes = 1_000_000

// closeReplaced is the WebSocket close code sent to a worker connection that a newer
// connection with the same worker ID replaced. Workers must not auto-reconnect on it,
// or two copies of one worker would push each other off forever.
const closeReplaced = 4001

// defaultReconnectGrace is how long jobs dispatched on a dropped worker connection stay
// alive, so a worker that reconnects can still deliver results it held.
const defaultReconnectGrace = 30 * time.Second

// Keepalive: ping workers regularly and drop connections that stop answering, so a
// phone that vanished (sleep, network switch) does not keep jobs assigned to it.
const (
	pingInterval = 25 * time.Second
	pongWait     = 60 * time.Second
)

var workerIDPattern = regexp.MustCompile(`^[A-Za-z0-9._-]{1,64}$`)

// allowedJobHost is the only host a job URL may point at. The extension opens the URL
// in the user's real browser, so anything else would let a caller open arbitrary pages.
const allowedJobHost = "access.pokemon.com"

type App struct {
	queue       *JobQueue
	authSecret  string
	workerToken string // lets workers on other machines connect (WORKER_TOKEN)
	localTrust  bool   // let loopback extension pages connect without a token
	started     time.Time
	logger      *log.Logger
	stats       *StatsStore
	grace       time.Duration // see defaultReconnectGrace

	mu      sync.Mutex
	workers map[string]*workerConn // connected workers by worker ID
	owners  map[string]*workerConn // job ID -> connection it was dispatched on
}

type workerConn struct {
	id     string
	conn   *websocket.Conn
	mu     sync.Mutex // serializes writes
	active int        // jobs in progress on this connection; guarded by App.mu
}

type wsMessage struct {
	Type      string `json:"type"`
	ID        string `json:"id,omitempty"`
	Status    string `json:"status,omitempty"`
	LoginCode string `json:"login_code,omitempty"`
}

// AppOptions holds the optional App settings.
type AppOptions struct {
	WorkerToken string
	// NoLocalTrust disables token-free access for loopback extension pages. Set it
	// when a reverse proxy on the same host forwards outside traffic to the helper.
	NoLocalTrust bool
}

func NewApp(queue *JobQueue, authSecret string, stats *StatsStore, logger *log.Logger, opts ...AppOptions) *App {
	if logger == nil {
		logger = log.Default()
	}
	if stats == nil {
		stats = NewStatsStore("")
	}
	var opt AppOptions
	if len(opts) > 0 {
		opt = opts[0]
	}
	return &App{
		queue:       queue,
		authSecret:  authSecret,
		workerToken: opt.WorkerToken,
		localTrust:  !opt.NoLocalTrust,
		started:     time.Now(),
		logger:      logger,
		stats:       stats,
		grace:       defaultReconnectGrace,
		workers:     make(map[string]*workerConn),
		owners:      make(map[string]*workerConn),
	}
}

func (a *App) Handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("/health", a.handleHealth)
	mux.HandleFunc("/api/v1/login-code", a.handleLoginCode)
	mux.HandleFunc("/ws", a.handleWS)
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		sendJSON(w, http.StatusNotFound, map[string]string{"status": "ERROR"})
	})
	return mux
}

func (a *App) handleHealth(w http.ResponseWriter, r *http.Request) {
	// The extension's control tab reads /health to explain connection problems, also
	// when the helper runs on another machine. Allow extension pages only.
	if origin := r.Header.Get("Origin"); isExtensionOrigin(origin) {
		w.Header().Set("Access-Control-Allow-Origin", origin)
		w.Header().Set("Vary", "Origin")
	}
	if r.Method != http.MethodGet {
		sendJSON(w, http.StatusNotFound, map[string]string{"status": "ERROR"})
		return
	}
	workers := a.workerCount()
	sendJSON(w, http.StatusOK, map[string]any{
		"status":              "OK",
		"extension_connected": workers > 0, // kept for older clients; means "any worker"
		"workers":             workers,
		"pending_jobs":        a.queue.Pending(),
		"uptime_seconds":      int(time.Since(a.started).Seconds()),
		"stats":               a.stats.Snapshot(),
	})
}

func (a *App) handleLoginCode(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		sendJSON(w, http.StatusNotFound, map[string]string{"status": "ERROR"})
		return
	}
	// Browsers attach Origin (and Sec-Fetch-Site) to every cross-site POST, even
	// no-cors "simple" ones. Dragonite never sends them, so refusing them stops any web
	// page the user visits from submitting jobs to the helper.
	if r.Header.Get("Origin") != "" || r.Header.Get("Sec-Fetch-Site") != "" {
		a.logger.Printf("[http] rejected browser-originated request from %s", r.RemoteAddr)
		sendJSON(w, http.StatusForbidden, map[string]string{"status": "ERROR"})
		return
	}
	if a.authSecret != "" && !secretEqual(r.Header.Get("x-auth-secret"), a.authSecret) {
		a.logger.Printf("[http] rejected unauthorized request from %s", r.RemoteAddr)
		sendJSON(w, http.StatusUnauthorized, map[string]string{"status": "ERROR"})
		return
	}

	r.Body = http.MaxBytesReader(w, r.Body, maxBodyBytes)
	defer r.Body.Close()
	raw, err := io.ReadAll(r.Body)
	if err != nil {
		sendJSON(w, http.StatusBadRequest, map[string]string{"status": "ERROR"})
		return
	}

	var payload JobPayload
	if err := json.Unmarshal(raw, &payload); err != nil {
		sendJSON(w, http.StatusBadRequest, map[string]string{"status": "ERROR"})
		return
	}
	if payload.Username == "" || payload.Password == "" || !validJobURL(payload.URL) {
		a.logger.Printf("[http] rejected malformed login-code request from %s", r.RemoteAddr)
		sendJSON(w, http.StatusBadRequest, map[string]string{"status": "ERROR"})
		return
	}

	a.logger.Printf("[http] login-code request for %s from %s", payload.Username, r.RemoteAddr)
	a.stats.RecordStarted()
	id, resultCh := a.queue.Add(payload)
	a.dispatch()

	var result AuthResult
	select {
	case result = <-resultCh:
	case <-r.Context().Done():
		// Dragonite gave up (or disconnected). Settle the job so it stops holding a slot,
		// and tell the worker to close its page.
		if a.queue.Settle(id, AuthResult{Status: "CANCELLED"}) {
			a.logger.Printf("[http] caller went away for %s, cancelling job %s", payload.Username, shortID(id))
		}
		result = <-resultCh
	}
	if result.Status == "TIMEOUT" || result.Status == "CANCELLED" {
		a.sendCancel(id, result.Status, payload.Username)
	}
	a.release(id)
	a.stats.RecordResult(result.Status)
	if result.Status == "CANCELLED" {
		return // nobody is listening for the response
	}
	code, body := toHTTP(result)
	a.logger.Printf("[http] responding %d %s for %s", code, body["status"], payload.Username)
	sendJSON(w, code, body)
}

// authorizeWorker decides whether a WebSocket client may become a worker. Jobs carry
// PTC passwords, so this is the helper's most sensitive check.
func (a *App) authorizeWorker(r *http.Request) bool {
	origin := r.Header.Get("Origin")
	// A browser page always sends its Origin and cannot forge it. Only extension pages
	// may be workers; any website is refused, token or not.
	if origin != "" && !isExtensionOrigin(origin) {
		return false
	}
	if a.workerToken != "" && secretEqual(workerTokenFrom(r), a.workerToken) {
		return true
	}
	// Token-free access only for this machine. Forwarded headers mean a reverse proxy
	// on this machine relayed someone else's connection, so loopback proves nothing.
	return a.localTrust && isLoopback(r.RemoteAddr) && !isForwarded(r)
}

func (a *App) handleWS(w http.ResponseWriter, r *http.Request) {
	if !a.authorizeWorker(r) {
		a.logger.Printf("[ws] rejected connection from %s (origin %q)", r.RemoteAddr, r.Header.Get("Origin"))
		http.Error(w, "forbidden", http.StatusForbidden)
		return
	}
	workerID := r.URL.Query().Get("worker")
	if workerID == "" {
		workerID = "default"
	}
	if !workerIDPattern.MatchString(workerID) {
		http.Error(w, "bad worker id", http.StatusBadRequest)
		return
	}

	upgrader := websocket.Upgrader{CheckOrigin: func(*http.Request) bool { return true }}
	conn, err := upgrader.Upgrade(w, r, nil)
	if err != nil {
		return
	}
	wc := &workerConn{id: workerID, conn: conn}

	a.mu.Lock()
	old := a.workers[workerID]
	a.workers[workerID] = wc
	a.mu.Unlock()

	if old != nil {
		a.logger.Printf("[ws] worker %s reconnected, replacing its old connection", workerID)
		old.close(closeReplaced, "replaced")
	}
	a.logger.Printf("[ws] worker %s connected from %s (%d connected)", workerID, r.RemoteAddr, a.workerCount())

	stopPing := make(chan struct{})
	conn.SetReadLimit(maxBodyBytes)
	_ = conn.SetReadDeadline(time.Now().Add(pongWait))
	conn.SetPongHandler(func(string) error {
		return conn.SetReadDeadline(time.Now().Add(pongWait))
	})
	go func() {
		t := time.NewTicker(pingInterval)
		defer t.Stop()
		for {
			select {
			case <-stopPing:
				return
			case <-t.C:
				if conn.WriteControl(websocket.PingMessage, nil, time.Now().Add(10*time.Second)) != nil {
					return
				}
			}
		}
	}()

	a.dispatch()

	for {
		var msg wsMessage
		if err := conn.ReadJSON(&msg); err != nil {
			break
		}
		_ = conn.SetReadDeadline(time.Now().Add(pongWait))
		switch msg.Type {
		case "result":
			if msg.ID == "" {
				continue
			}
			if !a.ownedBy(msg.ID, workerID) {
				a.logger.Printf("[ws] ignored result %s from %s: not its job", shortID(msg.ID), workerID)
				continue
			}
			a.logger.Printf("[ws] result %s %s from %s", shortID(msg.ID), msg.Status, workerID)
			a.release(msg.ID)
			a.queue.Settle(msg.ID, AuthResult{Status: msg.Status, LoginCode: msg.LoginCode})
			a.dispatch()
		case "challenge":
			if msg.ID != "" {
				a.stats.RecordChallenge()
				a.logger.Printf("[ws] challenge %s on %s", shortID(msg.ID), workerID)
			}
		}
	}
	close(stopPing)

	a.mu.Lock()
	if a.workers[workerID] == wc {
		delete(a.workers, workerID)
	}
	a.mu.Unlock()
	_ = conn.Close()
	a.logger.Printf("[ws] worker %s disconnected", workerID)
	a.expireJobsAfterGrace(wc)
}

// expireJobsAfterGrace fails the jobs a dropped connection still owned once the grace
// period ends. A worker that reconnects in time sends its held results first, so only
// jobs whose state was really lost (tab reload, app killed) end as ERROR, instead of
// hanging Dragonite until the full job timeout.
func (a *App) expireJobsAfterGrace(wc *workerConn) {
	time.AfterFunc(a.grace, func() {
		a.mu.Lock()
		var ids []string
		for id, owner := range a.owners {
			if owner == wc {
				ids = append(ids, id)
			}
		}
		a.mu.Unlock()
		for _, id := range ids {
			// Cancel before settling: settling wakes the HTTP handler, which releases
			// the job and so forgets which worker to cancel on.
			if a.queue.Has(id) {
				a.sendCancel(id, "ERROR", "")
			}
			if a.queue.Settle(id, AuthResult{Status: "ERROR"}) {
				a.logger.Printf("[ws] job %s lost with worker %s's connection", shortID(id), wc.id)
			}
			a.release(id)
		}
	})
}

// dispatch hands every waiting job to a connected worker, each time picking the one
// with the fewest jobs in progress. A worker may run jobs in parallel (browser tabs)
// or queue them itself (the Android app runs one at a time).
func (a *App) dispatch() {
	for {
		a.mu.Lock()
		var wc *workerConn
		for _, c := range a.workers {
			if wc == nil || c.active < wc.active || (c.active == wc.active && c.id < wc.id) {
				wc = c
			}
		}
		if wc == nil {
			a.mu.Unlock()
			return
		}
		job := a.queue.Take()
		if job == nil {
			a.mu.Unlock()
			return
		}
		a.owners[job.ID] = wc
		wc.active++
		a.mu.Unlock()

		a.logger.Printf("[auth] dispatch job %s for %s to worker %s", shortID(job.ID), job.Username, wc.id)
		if err := wc.send(map[string]any{
			"type":     "job",
			"id":       job.ID,
			"username": job.Username,
			"password": job.Password,
			"url":      job.URL,
		}); err != nil {
			a.logger.Printf("[ws] dispatch failed for %s: %v", shortID(job.ID), err)
			// The connection is broken. Drop it now, or the next jobs would pick
			// the same (idle-looking) worker and fail too.
			a.mu.Lock()
			if a.workers[wc.id] == wc {
				delete(a.workers, wc.id)
			}
			a.mu.Unlock()
			wc.close(websocket.CloseGoingAway, "send failed")
			a.release(job.ID)
			a.queue.Settle(job.ID, AuthResult{Status: "ERROR"})
		}
	}
}

// release forgets which connection owned a job, freeing that worker's slot.
func (a *App) release(id string) {
	a.mu.Lock()
	if wc, ok := a.owners[id]; ok {
		delete(a.owners, id)
		wc.active--
	}
	a.mu.Unlock()
}

// sendCancel tells the worker that owns a job to drop it. If the owning connection has
// since been replaced by a reconnect of the same worker, the new connection gets it.
// ownedBy reports whether the job was dispatched to this worker ID (on this or an
// earlier connection), so one worker cannot settle another worker's jobs.
func (a *App) ownedBy(id, workerID string) bool {
	a.mu.Lock()
	defer a.mu.Unlock()
	owner, ok := a.owners[id]
	return ok && owner.id == workerID
}

func (a *App) sendCancel(id, status, username string) {
	a.mu.Lock()
	var target *workerConn
	if owner, ok := a.owners[id]; ok {
		target = a.workers[owner.id]
	}
	a.mu.Unlock()
	if target == nil {
		return
	}
	if username != "" {
		a.logger.Printf("[ws] cancel job %s (%s) for %s", shortID(id), status, username)
	}
	_ = target.send(map[string]string{"type": "cancel", "id": id, "status": status})
}

func (a *App) workerCount() int {
	a.mu.Lock()
	defer a.mu.Unlock()
	return len(a.workers)
}

func (w *workerConn) send(v any) error {
	w.mu.Lock()
	defer w.mu.Unlock()
	_ = w.conn.SetWriteDeadline(time.Now().Add(10 * time.Second))
	return w.conn.WriteJSON(v)
}

func (w *workerConn) close(code int, reason string) {
	_ = w.conn.WriteControl(
		websocket.CloseMessage,
		websocket.FormatCloseMessage(code, reason),
		time.Now().Add(time.Second),
	)
	_ = w.conn.Close()
}

func workerTokenFrom(r *http.Request) string {
	if auth := r.Header.Get("Authorization"); strings.HasPrefix(auth, "Bearer ") {
		return strings.TrimPrefix(auth, "Bearer ")
	}
	return r.URL.Query().Get("token")
}

func secretEqual(got, want string) bool {
	return subtle.ConstantTimeCompare([]byte(got), []byte(want)) == 1
}

func isForwarded(r *http.Request) bool {
	return r.Header.Get("X-Forwarded-For") != "" || r.Header.Get("Forwarded") != "" || r.Header.Get("X-Real-IP") != ""
}

func validJobURL(raw string) bool {
	u, err := url.Parse(raw)
	return err == nil && u.Scheme == "https" && strings.EqualFold(u.Hostname(), allowedJobHost) && u.User == nil
}

func isLoopback(remoteAddr string) bool {
	host, _, err := net.SplitHostPort(remoteAddr)
	if err != nil {
		host = remoteAddr
	}
	ip := net.ParseIP(host)
	return ip != nil && ip.IsLoopback()
}

func isExtensionOrigin(origin string) bool {
	return strings.HasPrefix(origin, "chrome-extension://") ||
		strings.HasPrefix(origin, "moz-extension://")
}

func sendJSON(w http.ResponseWriter, code int, body any) {
	w.Header().Set("content-type", "application/json")
	w.WriteHeader(code)
	_ = json.NewEncoder(w).Encode(body)
}

func shortID(id string) string {
	if len(id) <= 8 {
		return id
	}
	return id[:8]
}

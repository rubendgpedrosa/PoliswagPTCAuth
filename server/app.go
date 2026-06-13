package main

import (
	"encoding/json"
	"log"
	"net/http"
	"sync"
	"time"

	"github.com/gorilla/websocket"
)

const maxBodyBytes = 1_000_000

type App struct {
	queue      *JobQueue
	authSecret string
	started    time.Time
	logger     *log.Logger
	stats      *StatsStore

	mu        sync.Mutex
	extension *extensionConn
}

type extensionConn struct {
	conn *websocket.Conn
	mu   sync.Mutex
}

type wsMessage struct {
	Type      string `json:"type"`
	ID        string `json:"id,omitempty"`
	Status    string `json:"status,omitempty"`
	LoginCode string `json:"login_code,omitempty"`
}

func NewApp(queue *JobQueue, authSecret string, stats *StatsStore, logger *log.Logger) *App {
	if logger == nil {
		logger = log.Default()
	}
	if stats == nil {
		stats = NewStatsStore("")
	}
	return &App{
		queue:      queue,
		authSecret: authSecret,
		started:    time.Now(),
		logger:     logger,
		stats:      stats,
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
	if r.Method != http.MethodGet {
		sendJSON(w, http.StatusNotFound, map[string]string{"status": "ERROR"})
		return
	}
	sendJSON(w, http.StatusOK, map[string]any{
		"status":              "OK",
		"extension_connected": a.extensionConnected(),
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
	if a.authSecret != "" && r.Header.Get("x-auth-secret") != a.authSecret {
		a.logger.Printf("[http] rejected unauthorized request from %s", r.RemoteAddr)
		sendJSON(w, http.StatusUnauthorized, map[string]string{"status": "ERROR"})
		return
	}

	r.Body = http.MaxBytesReader(w, r.Body, maxBodyBytes)
	defer r.Body.Close()

	var payload JobPayload
	if err := json.NewDecoder(r.Body).Decode(&payload); err != nil {
		sendJSON(w, http.StatusBadRequest, map[string]string{"status": "ERROR"})
		return
	}
	if payload.Username == "" || payload.Password == "" || payload.URL == "" {
		a.logger.Printf("[http] rejected malformed login-code request from %s", r.RemoteAddr)
		sendJSON(w, http.StatusBadRequest, map[string]string{"status": "ERROR"})
		return
	}

	a.logger.Printf("[http] login-code request for %s from %s", payload.Username, r.RemoteAddr)
	a.stats.RecordStarted()
	id, resultCh := a.queue.Add(payload)
	a.dispatchNext()

	result := <-resultCh
	if result.Status == "TIMEOUT" {
		a.sendCancel(id, "TIMEOUT", payload.Username)
	}
	a.stats.RecordResult(result.Status)
	code, body := toHTTP(result)
	a.logger.Printf("[http] responding %d %s for %s", code, body["status"], payload.Username)
	sendJSON(w, code, body)
}

func (a *App) handleWS(w http.ResponseWriter, r *http.Request) {
	upgrader := websocket.Upgrader{CheckOrigin: func(*http.Request) bool { return true }}
	conn, err := upgrader.Upgrade(w, r, nil)
	if err != nil {
		return
	}
	ext := &extensionConn{conn: conn}

	a.mu.Lock()
	old := a.extension
	a.extension = ext
	a.mu.Unlock()

	if old != nil {
		a.logger.Print("[ws] replacing existing extension connection")
		old.close("replaced by newer control tab")
	}
	a.logger.Print("[ws] extension connected")
	a.dispatchNext()

	for {
		var msg wsMessage
		if err := conn.ReadJSON(&msg); err != nil {
			break
		}
		switch msg.Type {
		case "result":
			if msg.ID == "" {
				continue
			}
			a.logger.Printf("[ws] result %s %s", shortID(msg.ID), msg.Status)
			a.queue.Settle(msg.ID, AuthResult{Status: msg.Status, LoginCode: msg.LoginCode})
			a.dispatchNext()
		case "challenge":
			if msg.ID != "" {
				a.stats.RecordChallenge()
				a.logger.Printf("[ws] challenge %s", shortID(msg.ID))
			}
		}
	}

	a.mu.Lock()
	if a.extension == ext {
		a.extension = nil
	}
	a.mu.Unlock()
	_ = conn.Close()
	a.logger.Print("[ws] extension disconnected")
}

func (a *App) dispatchNext() {
	a.mu.Lock()
	ext := a.extension
	a.mu.Unlock()
	if ext == nil {
		return
	}

	job := a.queue.Take()
	if job == nil {
		return
	}
	a.logger.Printf("[auth] dispatch job %s for %s", shortID(job.ID), job.Username)
	if err := ext.send(map[string]any{
		"type":     "job",
		"id":       job.ID,
		"username": job.Username,
		"password": job.Password,
		"url":      job.URL,
	}); err != nil {
		a.logger.Printf("[ws] dispatch failed for %s: %v", shortID(job.ID), err)
		a.queue.Settle(job.ID, AuthResult{Status: "ERROR"})
	}
}

func (a *App) sendCancel(id, status, username string) {
	a.mu.Lock()
	ext := a.extension
	a.mu.Unlock()
	if ext == nil {
		return
	}
	a.logger.Printf("[ws] cancel job %s (%s) for %s", shortID(id), status, username)
	_ = ext.send(map[string]string{"type": "cancel", "id": id, "status": status})
}

func (a *App) extensionConnected() bool {
	a.mu.Lock()
	defer a.mu.Unlock()
	return a.extension != nil
}

func (e *extensionConn) send(v any) error {
	e.mu.Lock()
	defer e.mu.Unlock()
	return e.conn.WriteJSON(v)
}

func (e *extensionConn) close(reason string) {
	e.mu.Lock()
	defer e.mu.Unlock()
	_ = e.conn.WriteControl(
		websocket.CloseMessage,
		websocket.FormatCloseMessage(websocket.CloseNormalClosure, reason),
		time.Now().Add(time.Second),
	)
	_ = e.conn.Close()
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

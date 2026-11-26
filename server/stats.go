package main

import (
	"encoding/json"
	"os"
	"path/filepath"
	"runtime"
	"sync"
)

type Stats struct {
	Total      int64 `json:"total"`
	Success    int64 `json:"success"`
	Failed     int64 `json:"failed"`
	Challenges int64 `json:"challenges"`
	Timeouts   int64 `json:"timeouts"`
}

type StatsStore struct {
	mu      sync.Mutex
	writeMu sync.Mutex // serializes file writes so an older snapshot never lands last
	path    string
	stats   Stats
}

func NewStatsStore(path string) *StatsStore {
	s := &StatsStore{path: path}
	if path == "" {
		return s
	}
	data, err := os.ReadFile(path)
	if err == nil {
		_ = json.Unmarshal(data, &s.stats)
	}
	return s
}

func (s *StatsStore) Snapshot() Stats {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.stats
}

func (s *StatsStore) RecordStarted() {
	s.update(func(st *Stats) { st.Total++ })
}

func (s *StatsStore) RecordResult(status string) {
	s.update(func(st *Stats) {
		if status == "SUCCESS" {
			st.Success++
		} else {
			st.Failed++
			if status == "TIMEOUT" {
				st.Timeouts++
			}
		}
	})
}

func (s *StatsStore) RecordChallenge() {
	s.update(func(st *Stats) { st.Challenges++ })
}

func (s *StatsStore) update(fn func(*Stats)) {
	s.mu.Lock()
	fn(&s.stats)
	path := s.path
	s.mu.Unlock()

	if path == "" {
		return
	}
	s.writeMu.Lock()
	defer s.writeMu.Unlock()
	snapshot := s.Snapshot() // latest state, taken while holding the write lock
	_ = os.MkdirAll(filepath.Dir(path), 0o700)
	tmp := path + ".tmp"
	data, err := json.MarshalIndent(snapshot, "", "  ")
	if err != nil {
		return
	}
	if err := os.WriteFile(tmp, data, 0o600); err == nil {
		_ = os.Rename(tmp, path)
	}
}

func defaultStateDir() string {
	if dir := os.Getenv("POLISWAG_STATE_DIR"); dir != "" {
		return dir
	}
	if runtime.GOOS == "windows" {
		if dir := os.Getenv("LOCALAPPDATA"); dir != "" {
			return filepath.Join(dir, "Poliswag Passport")
		}
	}
	if runtime.GOOS == "linux" {
		if dir := os.Getenv("XDG_STATE_HOME"); dir != "" {
			return filepath.Join(dir, "poliswag-passport")
		}
		if home, err := os.UserHomeDir(); err == nil {
			return filepath.Join(home, ".local", "state", "poliswag-passport")
		}
	}
	if dir, err := os.UserConfigDir(); err == nil {
		return filepath.Join(dir, "Poliswag Passport")
	}
	return ""
}

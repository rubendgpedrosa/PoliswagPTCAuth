package main

import (
	"crypto/rand"
	"encoding/hex"
	"sync"
	"time"
)

type JobPayload struct {
	Username string `json:"username"`
	Password string `json:"password"`
	URL      string `json:"url"`
}

type Job struct {
	ID       string `json:"id"`
	Username string `json:"username"`
	Password string `json:"password"`
	URL      string `json:"url"`
}

type queuedJob struct {
	payload JobPayload
	result  chan AuthResult
	timer   *time.Timer
}

type JobQueue struct {
	mu        sync.Mutex
	timeout   time.Duration
	waiting   []string
	jobs      map[string]*queuedJob
	settleJob func(id string, result AuthResult) bool
}

func NewJobQueue(timeout time.Duration) *JobQueue {
	q := &JobQueue{
		timeout: timeout,
		jobs:    make(map[string]*queuedJob),
	}
	q.settleJob = q.Settle
	return q
}

func (q *JobQueue) Add(payload JobPayload) (string, <-chan AuthResult) {
	id := newID()
	result := make(chan AuthResult, 1)

	q.mu.Lock()
	job := &queuedJob{payload: payload, result: result}
	job.timer = time.AfterFunc(q.timeout, func() {
		q.settleJob(id, AuthResult{Status: "TIMEOUT"})
	})
	q.jobs[id] = job
	q.waiting = append(q.waiting, id)
	q.mu.Unlock()

	return id, result
}

func (q *JobQueue) Take() *Job {
	q.mu.Lock()
	defer q.mu.Unlock()

	for len(q.waiting) > 0 {
		id := q.waiting[0]
		q.waiting = q.waiting[1:]
		job, ok := q.jobs[id]
		if !ok {
			continue
		}
		// If the queue-wait timer already fired, its Settle(TIMEOUT) is blocked on q.mu
		// and will settle this job as soon as we unlock. Skip it rather than dispatching
		// a job that is about to be cancelled (it would open a stray tab).
		if job.timer != nil && !job.timer.Stop() {
			continue
		}
		job.timer = time.AfterFunc(q.timeout, func() {
			q.settleJob(id, AuthResult{Status: "TIMEOUT"})
		})
		return &Job{
			ID:       id,
			Username: job.payload.Username,
			Password: job.payload.Password,
			URL:      job.payload.URL,
		}
	}
	return nil
}

func (q *JobQueue) Settle(id string, result AuthResult) bool {
	q.mu.Lock()
	job, ok := q.jobs[id]
	if !ok {
		q.mu.Unlock()
		return false
	}
	delete(q.jobs, id)
	for i, waitingID := range q.waiting {
		if waitingID == id {
			q.waiting = append(q.waiting[:i], q.waiting[i+1:]...)
			break
		}
	}
	if job.timer != nil {
		job.timer.Stop()
	}
	q.mu.Unlock()

	job.result <- result
	close(job.result)
	return true
}

// Has reports whether the job is still waiting or running (not yet settled).
func (q *JobQueue) Has(id string) bool {
	q.mu.Lock()
	defer q.mu.Unlock()
	_, ok := q.jobs[id]
	return ok
}

func (q *JobQueue) Pending() int {
	q.mu.Lock()
	defer q.mu.Unlock()
	return len(q.jobs)
}

func newID() string {
	var b [16]byte
	if _, err := rand.Read(b[:]); err != nil {
		return hex.EncodeToString([]byte(time.Now().Format(time.RFC3339Nano)))
	}
	b[6] = (b[6] & 0x0f) | 0x40
	b[8] = (b[8] & 0x3f) | 0x80
	return hex.EncodeToString(b[0:4]) + "-" +
		hex.EncodeToString(b[4:6]) + "-" +
		hex.EncodeToString(b[6:8]) + "-" +
		hex.EncodeToString(b[8:10]) + "-" +
		hex.EncodeToString(b[10:16])
}

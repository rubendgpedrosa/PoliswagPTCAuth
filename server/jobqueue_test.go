package main

import (
	"testing"
	"time"
)

func TestJobQueueAddTake(t *testing.T) {
	q := NewJobQueue(time.Second)
	id, _ := q.Add(JobPayload{Username: "u", Password: "p", URL: "http://x"})
	job := q.Take()
	if job == nil {
		t.Fatal("expected job")
	}
	if job.ID != id || job.Username != "u" || job.Password != "p" || job.URL != "http://x" {
		t.Fatalf("unexpected job: %+v", job)
	}
}

func TestJobQueueSettle(t *testing.T) {
	q := NewJobQueue(time.Second)
	id, result := q.Add(JobPayload{Username: "u", Password: "p", URL: "http://x"})
	q.Take()
	if !q.Settle(id, AuthResult{Status: "SUCCESS", LoginCode: "zzz"}) {
		t.Fatal("settle returned false")
	}
	got := <-result
	if got.Status != "SUCCESS" || got.LoginCode != "zzz" {
		t.Fatalf("unexpected result: %+v", got)
	}
}

func TestJobQueueTimeout(t *testing.T) {
	q := NewJobQueue(20 * time.Millisecond)
	_, result := q.Add(JobPayload{Username: "u", Password: "p", URL: "http://x"})
	got := <-result
	if got.Status != "TIMEOUT" {
		t.Fatalf("unexpected result: %+v", got)
	}
}

func TestJobQueueEmptyUnknownAndLateTimeout(t *testing.T) {
	q := NewJobQueue(20 * time.Millisecond)
	if q.Take() != nil {
		t.Fatal("expected empty queue")
	}
	if q.Settle("nope", AuthResult{Status: "ERROR"}) {
		t.Fatal("settle unknown returned true")
	}

	id, result := q.Add(JobPayload{Username: "u", Password: "p", URL: "http://x"})
	q.Take()
	if !q.Settle(id, AuthResult{Status: "SUCCESS", LoginCode: "ok"}) {
		t.Fatal("settle returned false")
	}
	time.Sleep(40 * time.Millisecond)
	got := <-result
	if got.Status != "SUCCESS" || got.LoginCode != "ok" {
		t.Fatalf("unexpected result: %+v", got)
	}
}

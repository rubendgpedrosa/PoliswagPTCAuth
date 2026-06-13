package main

import "testing"

func TestToHTTP(t *testing.T) {
	tests := []struct {
		in       AuthResult
		httpCode int
		status   string
		code     string
		hasCode  bool
	}{
		{AuthResult{Status: "SUCCESS", LoginCode: "abc"}, 200, "SUCCESS", "abc", true},
		{AuthResult{Status: "SUCCESS"}, 200, "SUCCESS", "", true},
		{AuthResult{Status: "INVALID"}, 400, "INVALID", "", false},
		{AuthResult{Status: "BANNED"}, 418, "BANNED", "", false},
		{AuthResult{Status: "TIMEOUT"}, 408, "TIMEOUT", "", false},
		{AuthResult{Status: "WAT"}, 500, "ERROR", "", false},
	}
	for _, tt := range tests {
		code, body := toHTTP(tt.in)
		if code != tt.httpCode || body["status"] != tt.status {
			t.Fatalf("toHTTP(%+v) = %d %+v", tt.in, code, body)
		}
		_, hasCode := body["login_code"]
		if hasCode != tt.hasCode || body["login_code"] != tt.code {
			t.Fatalf("login_code mismatch for %+v: %+v", tt.in, body)
		}
	}
}

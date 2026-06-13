package main

import "net/http"

type AuthResult struct {
	Status    string `json:"status"`
	LoginCode string `json:"login_code,omitempty"`
}

var statusCodes = map[string]int{
	"SUCCESS": http.StatusOK,
	"INVALID": http.StatusBadRequest,
	"BANNED":  http.StatusTeapot,
	"TIMEOUT": http.StatusRequestTimeout,
	"ERROR":   http.StatusInternalServerError,
}

func toHTTP(result AuthResult) (int, map[string]string) {
	status := result.Status
	code, ok := statusCodes[status]
	if !ok {
		status = "ERROR"
		code = statusCodes[status]
	}

	body := map[string]string{"status": status}
	if status == "SUCCESS" {
		body["login_code"] = result.LoginCode
	}
	return code, body
}

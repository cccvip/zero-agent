package main

import (
	"context"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"
)

// App struct
type App struct {
	ctx context.Context
}

// NewApp creates a new App application struct
func NewApp() *App {
	return &App{}
}

// startup is called when the app starts. The context is saved
// so we can call the runtime methods
func (a *App) startup(ctx context.Context) {
	a.ctx = ctx
}

// SendRequest describes an outgoing HTTP request.
type SendRequest struct {
	Method  string            `json:"method"`
	URL     string            `json:"url"`
	Headers map[string]string `json:"headers"`
	Body    string            `json:"body"`
}

// SendResponse describes the HTTP response returned to the frontend.
type SendResponse struct {
	Status     int               `json:"status"`
	StatusText string            `json:"status_text"`
	Headers    map[string]string `json:"headers"`
	Body       string            `json:"body"`
	DurationMs int64             `json:"duration_ms"`
	Error      string            `json:"error,omitempty"`
}

// SendRequest performs an HTTP request on behalf of the frontend.
func (a *App) SendRequest(req SendRequest) SendResponse {
	req.Method = strings.ToUpper(strings.TrimSpace(req.Method))
	if req.Method == "" {
		req.Method = http.MethodGet
	}
	if req.URL == "" {
		return SendResponse{Error: "url is required"}
	}

	var bodyReader io.Reader
	if req.Body != "" && req.Method != http.MethodGet && req.Method != http.MethodHead {
		bodyReader = strings.NewReader(req.Body)
	}

	target, err := url.Parse(req.URL)
	if err != nil {
		return SendResponse{Error: fmt.Sprintf("parse url: %v", err)}
	}
	if target.RawQuery != "" {
		target.RawQuery = target.Query().Encode()
	}

	outReq, err := http.NewRequestWithContext(context.Background(), req.Method, target.String(), bodyReader)
	if err != nil {
		return SendResponse{Error: fmt.Sprintf("build request: %v", err)}
	}

	for k, v := range req.Headers {
		if strings.TrimSpace(k) == "" {
			continue
		}
		outReq.Header.Set(k, v)
	}
	if outReq.Header.Get("User-Agent") == "" {
		outReq.Header.Set("User-Agent", "api-tester/1.0")
	}

	client := &http.Client{Timeout: 30 * time.Second}
	start := time.Now()
	resp, err := client.Do(outReq)
	durationMs := time.Since(start).Milliseconds()
	if err != nil {
		return SendResponse{Error: fmt.Sprintf("send request: %v", err), DurationMs: durationMs}
	}
	defer resp.Body.Close()

	respBody, err := io.ReadAll(io.LimitReader(resp.Body, 2<<20)) // max 2MB
	if err != nil {
		return SendResponse{Error: fmt.Sprintf("read response: %v", err), DurationMs: durationMs}
	}

	headers := make(map[string]string, len(resp.Header))
	for k, v := range resp.Header {
		headers[k] = strings.Join(v, ", ")
	}

	return SendResponse{
		Status:     resp.StatusCode,
		StatusText: resp.Status,
		Headers:    headers,
		Body:       string(respBody),
		DurationMs: durationMs,
	}
}

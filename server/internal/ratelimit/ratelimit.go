// Package ratelimit is a small keyed token bucket (per device, per address).
package ratelimit

import (
	"math"
	"sync"
	"time"
)

// Limiter allows Rate events per second with bursts of Burst, per key.
type Limiter struct {
	Rate  float64
	Burst float64
	Now   func() time.Time

	mu      sync.Mutex
	buckets map[string]*bucket
	swept   time.Time
}

type bucket struct {
	tokens float64
	last   time.Time
}

// New returns a limiter.
func New(rate, burst float64) *Limiter {
	return &Limiter{Rate: rate, Burst: burst, Now: time.Now, buckets: map[string]*bucket{}}
}

// Allow takes one token for key; when none is left it returns false and how long to wait.
func (l *Limiter) Allow(key string) (bool, time.Duration) {
	l.mu.Lock()
	defer l.mu.Unlock()
	now := l.Now()
	if now.Sub(l.swept) > time.Minute {
		l.sweep(now)
	}
	b, ok := l.buckets[key]
	if !ok {
		b = &bucket{tokens: l.Burst, last: now}
		l.buckets[key] = b
	}
	b.tokens = math.Min(l.Burst, b.tokens+now.Sub(b.last).Seconds()*l.Rate)
	b.last = now
	if b.tokens >= 1 {
		b.tokens--
		return true, 0
	}
	wait := time.Duration((1 - b.tokens) / l.Rate * float64(time.Second))
	return false, wait
}

// sweep forgets full buckets, so the map does not grow with every address ever seen.
func (l *Limiter) sweep(now time.Time) {
	for k, b := range l.buckets {
		if b.tokens+now.Sub(b.last).Seconds()*l.Rate >= l.Burst {
			delete(l.buckets, k)
		}
	}
	l.swept = now
}

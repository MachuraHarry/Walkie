# Reconnection Bug Analysis & Fix Plan

## Root Cause Analysis

After deep analysis of the entire connection flow, I've identified **5 critical bugs** that prevent proper reconnection:

### Bug 1: `isConnecting()` returns wrong value after disconnect
**File:** `WebSocketClient.kt` (line 79-83)
```kotlin
fun isConnecting(): Boolean {
    val state = (_hasConnectedEver && readyState == ReadyState.NOT_YET_CONNECTED) || _isReconnecting
    return state
}
```
**Problem:** After `onClose()` is called, `readyState` becomes `CLOSED` (not `NOT_YET_CONNECTED`). So `isConnecting()` returns `false` even when `_isReconnecting` is `true` — but only if `_hasConnectedEver` is false. Actually the real issue is: when `scheduleReconnect()` sets `_isReconnecting = true`, then `isConnecting()` should return true. But the check in `scheduleReconnect()` at line 272 does:
```kotlin
if (!isConnected && !isConnecting()) {
    reconnect()
}
```
This should work... BUT the problem is that `reconnect()` calls `super.reconnect()` which internally calls `connect()` again. However, `super.reconnect()` may fail silently if the old connection is still in CLOSED state and the underlying Java-WebSocket library doesn't properly handle reconnection.

### Bug 2: `connectionTimeoutJob` uses wrong CoroutineScope
**File:** `WebSocketClient.kt` (line 222-229)
```kotlin
connectionTimeoutJob = CoroutineScope(Dispatchers.IO).launch {
```
**Problem:** Creates a **new** CoroutineScope that is NOT linked to any lifecycle. This scope is never cancelled when the ViewModel or Activity is destroyed. More importantly, when `scheduleReconnect()` also creates a new `CoroutineScope(Dispatchers.IO)` at line 268 — these orphaned scopes can cause race conditions.

### Bug 3: `reconnect()` may not work with java-websocket library
**File:** `WebSocketClient.kt` (line 274)
```kotlin
reconnect()
```
**Problem:** The `java-websocket` library's `reconnect()` method calls `connect()` internally, but only if the websocket is in a closed/not-yet-connected state. However, after `onClose()` is called, the internal state may not be properly reset. The library's `reconnect()` creates a new thread, but if the old connection's internal `websocket` field is still in a bad state, it may throw an exception that is silently caught.

### Bug 4: LoginViewModel blocks reconnection attempts
**File:** `LoginViewModel.kt` (line 194-209)
```kotlin
if (connectionAttempts >= MAX_CONNECTION_ATTEMPTS) {
    // Max connection attempts reached
    return
}
```
**Problem:** `MAX_CONNECTION_ATTEMPTS = 3` and `connectionAttempts` is never reset when the WebSocket's own reconnect mechanism fires. The LoginViewModel's `connect()` is called from `LaunchedEffect(Unit)` in LoginScreen, which only runs once. After the WebSocket auto-reconnects, the LoginViewModel's `connectionAttempts` counter may already be exhausted, preventing manual reconnection.

### Bug 5: No reconnection trigger after successful reconnect
**File:** `ChannelListViewModel.kt` (line 71-80)
```kotlin
"connected" -> {
    _uiState.value = _uiState.value.copy(isConnected = true, isReconnecting = false)
    loadChannels()
}
```
**Problem:** When the WebSocket reconnects, the `connected` message is emitted. But if the user was already logged in before the disconnect, they need to re-login. The server's `handleDisconnect()` removes the client from the `clients` Map, so after reconnect, the server doesn't know who the client is. The client needs to re-send the `login` message after reconnection.

## Fix Plan

1. **Fix `reconnect()` to use `connect()` directly instead of `super.reconnect()`**
2. **Fix CoroutineScopes to use proper lifecycle management**
3. **Add auto-re-login after successful reconnection**
4. **Fix `isConnecting()` to properly detect reconnection state**
5. **Add connection state recovery in ChannelViewModel**

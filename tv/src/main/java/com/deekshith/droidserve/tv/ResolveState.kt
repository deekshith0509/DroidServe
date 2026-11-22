package com.deekshith.droidserve.tv

/** Async action state for resolver/UI. */
enum class ResolveState { Idle, Resolving, Ready, Failed }

/** VM connection state. */
enum class ConnectionState { Live, Reconnecting, Offline }
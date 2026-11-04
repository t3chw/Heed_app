package com.bol.app.ai.model

/**
 * [ModelManager.infer] ran past its timeout. The native generation has been
 * cancelled before this is thrown. Deliberately a plain [Exception], not a
 * CancellationException, so callers' normal failure handling runs.
 */
class InferenceTimeoutException(label: String, timeoutMs: Long) :
    Exception("$label: the model inference exceeded ${timeoutMs}ms")

/** [ModelManager.infer]'s `stopWhen` returned true (e.g. user pressed Stop). */
class InferenceStoppedException(label: String) :
    Exception("$label: stopped by caller")

/**
 * Another component unloaded the engine while this inference ran (Settings
 * change, calibration, model update) and the native generation was
 * cancelled. Not a user Stop and not a backend fault — safe to retry.
 */
class InferencePreemptedException(label: String) :
    Exception("$label: preempted by an engine unload")

/**
 * Native `Engine.initialize()` did not finish within its time budget (or
 * the load was abandoned mid-init). The backend is not retried this session.
 */
class EngineInitTimeoutException(backend: String, timeoutMs: Long) :
    Exception("$backend engine init did not finish within ${timeoutMs}ms")

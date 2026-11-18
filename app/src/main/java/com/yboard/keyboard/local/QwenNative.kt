package com.yboard.keyboard.local

/** JNI boundary. Model state is accessed only by LocalQwen's serial worker. */
internal object QwenNative {
    init { System.loadLibrary("yboard_qwen") }
    external fun load(path: String, threads: Int): Long
    external fun close(handle: Long)
    external fun cancel(): Long
    external fun clear(handle: Long)
    external fun prepare(handle: Long, context: String, generation: Long)
    external fun predict(handle: Long, context: String, prefix: String, generation: Long): Array<String>
    external fun score(handle: Long, context: String, words: Array<String>, generation: Long): DoubleArray
}

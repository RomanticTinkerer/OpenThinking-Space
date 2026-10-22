package com.dot.gallery.feature_node.presentation.util

// Minimal stand-in for ReFra's FileUtils.kt (only the helper the vendored editor UI uses).
inline fun String.sentenceCase(): String = lowercase().replaceFirstChar { it.uppercase() }

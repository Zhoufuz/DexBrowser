package com.dex.browser

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.webkit.JavascriptInterface
import android.widget.Toast

class DexJsBridge(
    private val context: Context,
    private val onTextSelected: ((String) -> Unit)? = null
) {

    @JavascriptInterface
    fun copyToClipboard(text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("web_copy", text))
        if (context is Activity) {
            context.runOnUiThread {
                Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
            }
        }
    }

    @JavascriptInterface
    fun onTextSelected(text: String) {
        onTextSelected?.invoke(text)
    }
}

val CLIPBOARD_POLYFILL_JS = """
(function(){
    if(window.__dexClipboardInjected) return;
    window.__dexClipboardInjected = true;
    if(!navigator.clipboard){
        navigator.clipboard = {};
    }
    navigator.clipboard.writeText = function(text){
        return new Promise(function(resolve, reject){
            try {
                if(window.DexBridge && DexBridge.copyToClipboard){
                    DexBridge.copyToClipboard(text);
                    resolve();
                } else {
                    var ta = document.createElement('textarea');
                    ta.value = text;
                    ta.style.position = 'fixed';
                    ta.style.left = '-9999px';
                    document.body.appendChild(ta);
                    ta.select();
                    document.execCommand('copy');
                    ta.remove();
                    resolve();
                }
            } catch(e){ reject(e); }
        });
    };
    navigator.clipboard.readText = function(){
        return Promise.reject(new DOMException('Read not supported','NotAllowedError'));
    };
})();
""".trimIndent()

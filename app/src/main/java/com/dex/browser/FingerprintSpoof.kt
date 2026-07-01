package com.dex.browser

/**
 * 指纹伪装 JS 注入代码生成器。
 * 根据用户设置伪造 navigator.language / languages / Intl.DateTimeFormat 时区。
 */
object FingerprintSpoof {

    fun buildScript(language: String, timezone: String): String {
        return """
(function(){
    if(window.__dexFingerprintSpoofed) return;
    window.__dexFingerprintSpoofed = true;

    // 伪造 navigator.language / languages
    try {
        Object.defineProperty(navigator, 'language', {get:function(){return '$language';}});
        Object.defineProperty(navigator, 'languages', {get:function(){return ['$language'];}});
    } catch(e){}

    // 伪造时区（通过劫持 Intl.DateTimeFormat）
    try {
        (function(){
            var RealDTF = Intl.DateTimeFormat;
            var Spoof = function(){
                var args = Array.prototype.slice.call(arguments);
                if(args.length < 2){ args[1] = {}; }
                if(!args[1].timeZone){ args[1].timeZone = '$timezone'; }
                return RealDTF.apply(this, args);
            };
            Spoof.prototype = RealDTF.prototype;
            Spoof.supportedLocalesOf = RealDTF.supportedLocalesOf;
            Intl.DateTimeFormat = Spoof;
        })();
    } catch(e){}

    // 伪造 Date.getTimezoneOffset（简化版，仅影响常规检测）
    try {
        var offsets = {
            'Asia/Shanghai': -480, 'Asia/Tokyo': -540, 'Asia/Seoul': -540,
            'Asia/Singapore': -480, 'Asia/Hong_Kong': -480,
            'Australia/Sydney': -660, 'Europe/Moscow': -180,
            'Europe/Paris': -60, 'Europe/Berlin': -60, 'Europe/London': 0,
            'America/New_York': 300, 'America/Los_Angeles': 480
        };
        var target = offsets['$timezone'];
        if(target !== undefined){
            Date.prototype.getTimezoneOffset = function(){ return target; };
        }
    } catch(e){}
})();
        """.trimIndent()
    }
}

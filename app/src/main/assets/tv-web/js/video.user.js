// ========== 防重复注入检测 ==========
if (window.__MARMOT_SCRIPT_INJECTED__) {
    console.log('[Marmot] 脚本已注入，跳过重复执行');
} else {
    window.__MARMOT_SCRIPT_INJECTED__ = true;
    console.log('[Marmot] 脚本首次注入，开始初始化');
// ========== 原有脚本内容 ==========
console.log("helloWorld");

// 从 URL 参数获取值
function getUrlParam(name) {
    const url = new URL(window.location.href);
    return url.searchParams.get(name);
}

// 挂到 window 上，确保外部脚本能访问
window._tvState={
    platform:"//platform//"
};

function extractDomain(url) {
    const match = url.match(/^(https?:\/\/[^/?#]+)/i);
    return match ? match[1] : null;
}
const _tvLoadRes={
    js(scrJs){
        let script = document.createElement('script');
        script.setAttribute('type', 'text/javascript');
        //charset="utf-8"
        script.setAttribute('charset','utf-8');
        script.src = scrJs;
        script.async = false;
        document.body.appendChild(script);
    },
    css(scrCss){
        let script = document.createElement('link');
        script.setAttribute('rel', 'stylesheet');
        script.setAttribute('type', 'text/css');
        script.href = scrCss;
        script.async = false;
        document.head.appendChild(script);
    },
    jsBottom(scrJs){
        let script = document.createElement('script');
        script.setAttribute('type', 'text/javascript');
        script.setAttribute('charset','utf-8');
        script.src = scrJs;
        script.async = false;
        document.head.appendChild(script);
    }
};
function loadDetailByUrl(url){
    if(url.startsWith("https://v.qq.com/x/cover/")) {
        return "qq";
    }
    if(url.startsWith("https://www.iqiyi.com/")){
        return "iqiyi";
    }
    if(url.startsWith("https://www.bilibili.com/bangumi/play/")){
        return "bili";
    }
    if(url.startsWith("https://www.mgtv.com/b/")){
        return "mgtv";
    }
    if(url.startsWith("https://tv.cctv.com/")){
        return "cctvideo";
    }
    if(url.startsWith("https://v.youku.com/")){
        return "youku";
    }
    if(url.startsWith("https://www.bestv.com.cn/web/play/")){
        return "bestv";
    }
    if (url.indexOf("www.douyin.com") !== -1) {
        return "douyin";
    }
    return null;
}
(function(){
    console.log("_tvState",_tvState);
    let detailPath=loadDetailByUrl(window.location.href);
    if(null==detailPath){
        console.log("detailPath null");
        return;
    }
    let domain =  "marmot://res.vonchange.com";
    if(_tvState.platform==="android"){
       domain=extractDomain(window.location.href);
    }
    //https://www.tcsrm.cn/tvradio/tczhpd.html
    _tvLoadRes.js(domain+"/tv-web/js/zepto.min.js");
    _tvLoadRes.js(domain+"/tv-web/js/common.js");
    _tvLoadRes.js(domain+`/tv-web/js/${detailPath}/detail.js`);
})();

}
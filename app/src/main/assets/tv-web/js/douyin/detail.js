/**
 * 抖音详情页遥控脚本（marmot-tv-web）
 * 无外部依赖，仅用原生 DOM，避免 Zepto「init or use must be first call」等问题。
 * 监听 keydown + _ctrlx 供 App 下发 command 调用。
 */
(function () {
    'use strict';

    if (localStorage.getItem('isClearScreen') === null) {
        localStorage.setItem('isClearScreen', 'false');
    }

    function simulateKeyPress(key, opts) {
        opts = opts || {};
        var keyUpper = key.length === 1 ? key.toUpperCase() : key;
        var code = opts.code || (key.length === 1 ? 'Key' + keyUpper : key);
        var keyCode = opts.keyCode || (key.length === 1 ? keyUpper.charCodeAt(0) : 0);
        var evOpt = { key: key, code: code, keyCode: keyCode, which: keyCode, bubbles: true, cancelable: true, view: window };
        document.body.dispatchEvent(new KeyboardEvent('keydown', evOpt));
        document.body.dispatchEvent(new KeyboardEvent('keyup', evOpt));
    }

    function qs(selector, root) {
        root = root || document;
        return root.querySelector(selector);
    }

    function qsAll(selector, root) {
        root = root || document;
        return root.querySelectorAll(selector);
    }

    function clickEl(el) {
        if (el) el.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }));
    }

    function clickOrKey(selector, key) {
        var el = qs(selector);
        if (el) clickEl(el);
        else simulateKeyPress(key);
    }

    // 从 ?recommend=1 进来会跳到 /jingxuan（精选），需自动点一次「推荐」进入推荐流
    function tryClickRecommendTab() {
        var path = window.location.pathname || '';
        if (path.indexOf('jingxuan') === -1) return false;
        var el = qs('a[href*="recommend"]') || qs('[data-e2e*="recommend"]');
        if (el) {
            clickEl(el);
            return true;
        }
        var candidates = qsAll('a, [role="tab"], [class*="nav"] [class*="item"]');
        for (var i = 0; i < candidates.length; i++) {
            var node = candidates[i];
            if (node.textContent && node.textContent.trim() === '推荐') {
                var rect = node.getBoundingClientRect();
                if (rect.width && rect.height && rect.left < 300) {
                    clickEl(node);
                    return true;
                }
            }
        }
        return false;
    }

    function ensureRecommendTabOnJingxuan() {
        var attempts = 0;
        var t = setInterval(function () {
            if (tryClickRecommendTab() || ++attempts >= 6) clearInterval(t);
        }, 500);
    }

    var _ctrlx = {
        // 上一条：页面内派发 ArrowUp，兼容 PC 遥控
        prev: function () {
            simulateKeyPress('ArrowUp', { code: 'ArrowUp', keyCode: 38 });
        },
        // 下一条：页面内派发 ArrowDown，兼容 PC 遥控
        next: function () {
            simulateKeyPress('ArrowDown', { code: 'ArrowDown', keyCode: 40 });
        },
        // 全屏：参考 douyin_plugin.js，用 y 键切换网页全屏（有全屏按钮时直接按 y，否则轮询后按 y）
        toggleFullScreen: function () {
            if (qs('.xgplayer-page-full-screen')) {
                simulateKeyPress('y', { code: 'KeyY', keyCode: 89 });
            } else {
                fullScreen();
            }
        },
        // 弹幕开关：脚本内派发 B 键，交给页面/抖音自己处理
        toggleDanmaku: function () {
            simulateKeyPress('b', { code: 'KeyB', keyCode: 66 });
        },
        // 清屏：用 J 键实现；同时切换 localStorage 供 observer 点选
        toggleClearScreen: function () {
            simulateKeyPress('j', { code: 'KeyJ', keyCode: 74 });
        },
        focusComment: function () { focusComment(); },
        goAuthor: function () { goAuthorOrLive(); },
        like: function () { like(); },
        toggleAutoPlay: function () { clickOrKey('.xgplayer-autoplay-setting button, .automatic-continuous button.xg-switch', 'k'); },
        collect: function () { clickOrKey('[data-e2e="video-player-collect"]', 'c'); },
        share: function () { clickOrKey('[data-e2e="video-player-share"]', 's'); },
        related: function () { clickOrKey('[data-e2e="video-play-more"]', 'r'); }
    };
    window._ctrlx = _ctrlx;

    // 与 douyin_plugin.js 一致：轮询直到出现全屏按钮（登录后），再按 y 键进入网页全屏
    function fullScreen() {
        var attempts = 0, maxAttempts = 90;
        var intervalId = setInterval(function () {
            if (qs('#douyin-login-new-id')) { attempts = 0; return; }
            attempts++;
            if (qs('.xgplayer-page-full-screen')) {
                simulateKeyPress('y', { code: 'KeyY', keyCode: 89 });
                clearInterval(intervalId);
            } else if (attempts >= maxAttempts) clearInterval(intervalId);
        }, 1000);
    }


    function setupClearScreenObserver() {
        setInterval(function () {
            if (localStorage.getItem('isClearScreen') !== 'true') return;
            var labels = qsAll('.xgplayer-setting-label');
            for (var i = 0; i < labels.length; i++) {
                var title = qs('.xgplayer-setting-title', labels[i]);
                if (title && title.textContent.trim() === '清屏') {
                    var btn = qs('button.xg-switch', labels[i]);
                    if (btn && btn.className.indexOf('xg-switch-checked') < 0) clickEl(btn);
                    break;
                }
            }
        }, 500);
    }

    function like() {
        var el = qs('[data-e2e="video-player-digg"]') || qs('[class*="like"]') || qs('.xgplayer-like') || qs('span[class*="Digg"]');
        if (el) clickEl(el);
        else simulateKeyPress('l');
    }

    function goAuthorOrLive() {
        var el = qs('a[data-e2e="video-avatar"]') || qs('.xgplayer-author') || qs('[class*="author"]') || qs('a[class*="avatar"]');
        if (el) clickEl(el);
        else simulateKeyPress('f');
    }

    function focusComment() {
        var el = qs('[data-e2e="feed-comment-icon"]') || qs('.xgplayer-comment') || qs('[class*="comment"]');
        if (el) clickEl(el);
        else simulateKeyPress('x');
    }

    function handleKeyDown(e) {
        var active = document.activeElement;
        var isTyping = active && (active.tagName === 'INPUT' || active.tagName === 'TEXTAREA' || active.isContentEditable);
        if (isTyping) return;

        // 忽略由脚本合成的键盘事件，避免 simulateKeyPress → handleKeyDown → _ctrlx 再次触发形成递归。
        if (!e.isTrusted) return;

        var key = (e.key || '').toLowerCase();
        var code = (e.code || '').toLowerCase();

        if (key === 'h' || code === 'keyh') {
            e.preventDefault();
            _ctrlx.toggleFullScreen();
            return;
        }
        if (key === 'z' || code === 'keyz') {
            e.preventDefault();
            _ctrlx.like();
            return;
        }
        if (key === 'x' || code === 'keyx') {
            e.preventDefault();
            _ctrlx.focusComment();
            return;
        }
    
        if (key === 'k' || code === 'keyk') {
            e.preventDefault();
            _ctrlx.toggleAutoPlay();
            return;
        }
        if (key === 'b' || code === 'keyb') {
            e.preventDefault();
            _ctrlx.toggleDanmaku();
            return;
        }
        if (key === 'j' || code === 'keyj') {
            e.preventDefault();
            _ctrlx.toggleClearScreen();
            return;
        }
        if (key === 'f' || code === 'keyf') {
            e.preventDefault();
            _ctrlx.goAuthor();
            return;
        }
        if (key === 'm' || code === 'keym') {
            e.preventDefault();
            if (window._tvController && typeof _tvController.toggleMute === 'function') _tvController.toggleMute();
            return;
        }
    }

    document.addEventListener('keydown', handleKeyDown, true);
    setupClearScreenObserver();
    fullScreen();
    if ((window.location.pathname || '').indexOf('jingxuan') !== -1) {
        setTimeout(ensureRecommendTabOnJingxuan, 300);
    }
    console.log('[Douyin detail] 抖音遥控脚本已加载');
})();

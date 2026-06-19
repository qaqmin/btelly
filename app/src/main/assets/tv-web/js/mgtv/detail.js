const _ctrlx = {
    toggleFullScreen() {
        // 等待元素加载后再绑定键盘事件
        _tvFunc.check(
            () => document.getElementById("mgtv-player-wrap") !== null,
            () => {
                const elem = document.getElementById("mgtv-player-wrap");
                _tvFunc.addKeyFullScreen(elem);
                _tvFunc.webFullScreen(elem,true);
            }
        );
    },
    /** 开关弹幕：点击弹幕开关（参考 mgtv.js toggleDanmu） */
    toggleDanmaku() {
        const newSwitch = $$('div[class*="_danmuSwitcher_"]').first();
        if (newSwitch.length) {
            newSwitch.click();
            return;
        }
        const oldSwitch = $$('#barrage-switch').first();
        if (oldSwitch.length) {
            oldSwitch.click();
            return;
        }
        const legacySwitch = $$('.kui-danmu-switch .kui-switch-input').first();
        if (legacySwitch.length) {
            legacySwitch.click();
        }
    },
    /**
     * 发送弹幕：找输入框和发送按钮，填内容后点击发送（参考 mgtv.js sendDanmu）
     * @param {string} text - 弹幕内容
     * @returns {boolean} 是否执行成功
     */
    sendDanmu(text) {
        if (typeof text !== 'string' || !text.trim()) {
            console.warn('[mgtv/detail] 弹幕内容不能为空');
            return false;
        }
        const txt = text.trim();
        const input = $$('input[class*="_input_"]').first();
        const sendBtn = $$('div[class*="_senderBtn_"]').first();
        if (!input.length || !sendBtn.length) {
            console.warn('[mgtv/detail] 未找到弹幕输入框或发送按钮');
            return false;
        }
        const el = input[0];
        const nativeSetter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
        nativeSetter.call(el, txt);
        el.dispatchEvent(new Event('input', { bubbles: true }));
        setTimeout(() => {
            sendBtn.click();
            setTimeout(() => {
                nativeSetter.call(el, '');
                el.dispatchEvent(new Event('input', { bubbles: true }));
            }, 300);
        }, 150);
        return true;
    },
    showLogin() {
        $$(".user img").click();
    },
    hideLogin() {
        $$(".main-close").click();
    }
};

const _data = {
    initialized: false, // 防止重复初始化
    init() {
        if (this.initialized) {
            console.log("[_data] 已初始化，跳过重复执行");
            return;
        }
        this.initialized = true;

        this.collectAllData();
    },

    async collectAllData() {
       // 全屏
        _ctrlx.toggleFullScreen();
        // 各个数据独立异步获取并发送，互不阻塞
        this.sendLoginStatus();
        this.sendDanmakuSupport();
        // 等待基础元素加载
        await new Promise(resolve => _tvFunc.check(() => $$(".clarityBtn").length > 0, resolve));
        this.sendVideoState();
        this.sendQualities();
    },

    /** 上报当前页是否支持弹幕开关、发送弹幕（App 据此决定是否展示弹幕按钮） */
    sendDanmakuSupport() {
        _apiX.postMessage({
            type: 'danmakuSupport',
            data: {
                supportsDanmakuToggle: true,
                supportsSendDanmu: true
            }
        });
    },

    async sendLoginStatus() {
        try {
            const isLogin = $$("#m-topheader .user").find(".vip").length > 0;
            if(isLogin){
                _ctrlx.toggleFullScreen();
            }
            _apiX.postMessage({
                type: 'loginStatus',
                data: { isLogin }
            });
        } catch (e) {
            console.error("Send loginStatus error", e);
        }
    },

    async sendVideoState() {
        // 等待 video 元素准备好后再发送状态
        _tvFunc.check(
            function() {
                let video = _tvFunc.getVideo();
                return video && video.readyState >= 1; // HAVE_METADATA
            },
            function() {
                // 立即发送一次状态
                _tvController.videoState();

                // 每 10 秒定时发送视频状态
                if (window._videoStateInterval) {
                    clearInterval(window._videoStateInterval);
                }
                window._videoStateInterval = setInterval(function() {
                    _tvController.videoState();
                }, 10000);
            }
        );
    },

    async sendQualities() {
        try {
            const qualities = await this.fetchQualities();
            if (qualities && qualities.length > 0) {
                _apiX.postMessage({
                    type: 'qualities',
                    data: { qualities }
                });
            }
        } catch (e) {
            console.error("Send qualities error", e);
        }
    },

    async fetchQualities() {
        return new Promise((resolve) => {
            console.log('[fetchQualities] 开始等待画质按钮加载...');
            // 等待画质按钮数据加载完成
            _tvFunc.check(
                function() {
                    const hasButton = $$(".clarityBtn").find("[class^='_Button']").length > 0;
                    const hasBarName = $$(".clarityBtn").find("[class^='_barName']").length > 0;
                    console.log('[fetchQualities] 检查元素:', { hasButton, hasBarName });
                    return hasButton && hasBarName;
                },
                function() {
                    console.log('[fetchQualities] 元素已就绪，开始获取画质列表');
                    let qualities = [];
                    let newIndex = 0;
                    let hzNow = $$(".clarityBtn").find("[class^='_Button']").text().replace("SDR", "").trim();

                    $$(".clarityBtn").find("[class^='_barName']").each(function (index, item) {
                        let hzName = $$(item).text();
                        if (hzName.includes("客户端")) return;

                        let isVip = false;
                        if (hzName.includes("VIP")) {
                            isVip = true;
                            hzName = hzName.replace("VIP", "");
                        }

                        let id = newIndex.toString();
                        // 给可点击的父元素设置 ID
                        let clickableElem = $$(item).parent();
                        clickableElem.attr("id", "xhz-" + id);

                        qualities.push({
                            id: id,
                            name: hzName,
                            isVip: isVip,
                            isCurrent: hzName.includes(hzNow),
                            action:`$$("#xhz-${id}").click();`,
                            level: _tvFunc.hzLevel(hzName, 1)
                        });
                        newIndex++;
                    });
                    console.log('[fetchQualities] 获取到', qualities.length, '个画质选项');
                    resolve(qualities);
                }
            );
        });
    }
};

(function () {
    $$(function () {
        _data.init();
    });
})();

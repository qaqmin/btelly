const _ctrlx = {
    toggleFullScreen() {
        _apiX.postMessage({type: 'keyCode', data: "F"});
    },
    /** 开关弹幕：直接点击弹幕按钮（参考 aiqiyi.js toggleDanmu，不监听 d 键） */
    toggleDanmaku() {
        const btnSet = $$(".player-buttons_danmuBtnSet__28a4c");
        if (btnSet.length) {
            const danmuBtn = btnSet.find('div[style*="cursor: pointer"]').first();
            if (danmuBtn.length) {
                danmuBtn.click();
                return;
            }
        }
        const barragePanel = $$("#qiyibs-barrage-control-panel");
        if (barragePanel.length) {
            const barrageOn = $$("#barrage_on");
            const barrageHalf = $$("#barrage_half");
            const barrageOff = $$("#barrage_off");
            if (barrageOn.length && barrageOn.hasClass("qiyibs-svg-on") && !barrageOn.hasClass("dn")) {
                barrageOn.click();
                return;
            }
            if (barrageOff.length && barrageOff.hasClass("qiyibs-svg-off") && !barrageOff.hasClass("dn")) {
                barrageOff.click();
                return;
            }
            if (barrageHalf.length && barrageHalf.hasClass("qiyibs-svg-half") && !barrageHalf.hasClass("dn")) {
                barrageHalf.click();
            }
        }
    },
    /**
     * 发送弹幕：在新版弹幕控制区找输入框和发送按钮并提交
     * 结构：.player-buttons_danmuBtnSet__xxx 内 input + span「发送」
     * @param {string} text - 弹幕内容
     * @returns {boolean} 是否执行成功
     */
    sendDanmu(text) {
        if (typeof text !== "string" || !text.trim()) {
            console.warn("[iqiyi/detail] 弹幕内容不能为空");
            return false;
        }
        const txt = text.trim();
        const container = $$('[class*="player-buttons_danmuBtnSet"]');
        if (!container.length) {
            console.warn("[iqiyi/detail] 未找到弹幕控制区");
            return false;
        }
        const input = container.find('input[type="text"]').first();
        const sendSpan = container.find('span').filter(function() {
            return $$(this).text().trim() === '发送';
        }).first();
        if (!input.length || !sendSpan.length) {
            console.warn("[iqiyi/detail] 未找到弹幕输入框或发送按钮");
            return false;
        }
        const el = input[0];
        input.val(txt);
        el.value = txt;
        // 触发多种事件，让页面识别“已有输入”并解除发送按钮的 disabled
        el.dispatchEvent(new Event("input", { bubbles: true }));
        el.dispatchEvent(new Event("change", { bubbles: true }));
        el.dispatchEvent(new Event("keyup", { bubbles: true }));
        // 等页面更新按钮可点后再点发送（有输入后发送按钮才可点击）
        setTimeout(() => sendSpan.click(), 200);
        return true;
    },
    showLogin() {
        $$("#btn_user").click();
    },
    hideLogin() {
        // 关闭登录弹窗
        $$('[class*="simple-buttons_close_btn"]').click();
    }
};

const _data = {
    initialized: false,

    init() {
        if (this.initialized) {
            console.log("[_data] 已初始化，跳过重复执行");
            return;
        }
        this.initialized = true;
        this.collectAllData();
    },

    async collectAllData() {
        // 等待播放器加载
        await new Promise(resolve =>
            _tvFunc.check(() => $$("#right").length > 0, resolve)
        );


        // 移除 logo
        $$(".iqp-logo-box").remove();
        // 各个数据独立异步获取并发送，互不阻塞
        this.sendLoginStatus();
        this.sendDanmakuSupport();
        this.sendVideoState();
        this.sendQualities();
    },

    /** 上报当前页是否支持弹幕开关、发送弹幕（App 据此决定是否展示弹幕按钮） */
    sendDanmakuSupport() {
        _apiX.postMessage({
            type: "danmakuSupport",
            data: {
                supportsDanmakuToggle: true,
                supportsSendDanmu: false  // 新版 UI 有输入后发送才可点，脚本模拟暂不可靠，先不展示发送弹幕
            }
        });
    },

    async sendLoginStatus() {
        try {
            let loginText = $$("#btn_user").text();
            let isLogin = loginText !== "登录";
            _apiX.postMessage({
                type: 'loginStatus',
                data: { isLogin }
            });
        } catch (e) {
            console.error("Send loginStatus error", e);
        }
    },

    async sendVideoState() {
        _tvFunc.check(
            function() {
                let video = _tvFunc.getVideo();
                return video && video.readyState >= 1;
            },
            function() {
                _tvController.videoState();

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
            await new Promise(resolve =>
                _tvFunc.check(() => _tvFunc.getVideo().duration > 150, resolve,1000,150)
            );
            // 全屏
            _ctrlx.toggleFullScreen();
            // 音量100
            _tvFunc.volume100();
            const qualities = this.fetchQualities();
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

    fetchQualities() {
        let qualities = [];
        let newIndex = 0;

        $$(".iqp-txt-stream").each(function(index, item) {
            let hzName = $$(item).find(".iqp-stream").text();
            if (!hzName || hzName.includes("客户端")) {
                return true;
            }

            let id = newIndex.toString();
            $$(item).attr("id", "xhz-" + id);

            qualities.push({
                id: id,
                name: hzName,
                isVip: false,
                isCurrent: false,
                action: `$$("#xhz-${id}").click();`,
                level: _tvFunc.hzLevel(hzName, 1)
            });
            newIndex++;
        });

        // 获取当前画质
        let hzNow = $$(".iqp-btn-definition").find(".sub").text();
        qualities.forEach(q => {
            if (q.name.includes(hzNow)) {
                q.isCurrent = true;
            }
        });

        return qualities;
    }
};

// 初始化
$$(function() {
    _tvFunc.check(function() { return $$("#btn_user").length > 0; }, function() {
        let loginText = $$("#btn_user").text();
        console.log("isLogin:: " + (loginText !== "登录"));
        _data.init();
    });
});

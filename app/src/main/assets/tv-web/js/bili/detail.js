const _ctrlx = {
    toggleFullScreen() {
        //_tvFunc.fullscreen("#bilibili-player-wrap");
        _apiX.postMessage({type: 'keyCode', data: "F"});
     /*   _tvFunc.check(
            () => document.getElementById("bilibili-player-wrap") !== null,
            () => {
                const elem = document.getElementById("bilibili-player-wrap");
                //_tvFunc.addKeyFullScreen(elem);
                _tvFunc.webFullScreen(elem);
                $$(".bpx-player-sending-bar").css("display", "none");
                $$("#bilibili-player-wrap").css("padding-right", "0");
            }
        );*/
    },
    /** 开关弹幕：发送 keyCode D，由 TV 端或页面快捷键处理 */
    toggleDanmaku() {
        _apiX.postMessage({type: 'keyCode', data: "d"});
    },
    /**
     * 发送弹幕：查找输入框填文并点击发送（参考 bili_plugin，用 Zepto 简化）
     * @param {string} text - 弹幕内容
     * @returns {boolean} 是否执行成功
     */
    sendDanmu(text) {
        if (typeof text !== 'string' || !text.trim()) {
            console.warn("[detail] 弹幕内容不能为空");
            return false;
        }
        const input = $$(".bpx-player-dm-input");
        const sendBtn = $$(".bpx-player-dm-btn-send");
        if (!input.length || !sendBtn.length) {
            console.warn("[detail] 未找到弹幕输入框或发送按钮");
            return false;
        }
        const el = input[0];
        if (el.disabled || sendBtn[0].disabled) {
            console.warn("[detail] 弹幕功能不可用");
            return false;
        }
        input.val(text.trim());
        el.dispatchEvent(new Event("input", { bubbles: true }));
        sendBtn.click();
        setTimeout(function () {
            input.val("");
            el.dispatchEvent(new Event("input", { bubbles: true }));
        }, 500);
        return true;
    },
    showLogin() {
        $$(".go-login-btn").click();
    },
    hideLogin() {
        $$(".bili-mini-close-icon").click();
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
            _tvFunc.check(() => $$(".bpx-player-ctrl-quality-menu-item").length > 0, resolve)
        );

        // 全屏
        _ctrlx.toggleFullScreen();
        // 音量100
        _tvFunc.volume100();
        // 移除投币弹窗
        setInterval(function() {
            $$(".main-container").find("[class^='dialogcoin_coin_dialog_mask']").remove();
        }, 2000);

        // 各个数据独立异步获取并发送，互不阻塞
        this.sendLoginStatus();
        this.sendDanmakuSupport();
        this.sendVideoState();
        this.sendQualities();
    },

    /** 新发消息：上报当前页是否支持弹幕开关、发送弹幕（App 据此决定是否展示弹幕按钮） */
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
            _tvFunc.check(function() { return __playinfo__; }, function() {
                const isLogin = __playinfo__.result.user_status.is_login;
                    _apiX.postMessage({
                        type: 'loginStatus',
                        data: { isLogin }
                    });
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
                return video && video.readyState >= 1;
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

        $$(".bpx-player-ctrl-quality-menu-item").each(function(index, item) {
            let hzName = $$(item).find(".bpx-player-ctrl-quality-text").text();
            if (!hzName || hzName.includes("客户端") || hzName.includes("高码率")) {
                return true;
            }

            let isVip = $$(item).find(".bpx-player-ctrl-quality-badge-bigvip").length > 0;
            let isCurrent = $$(item).hasClass("bpx-state-active");
            let id = newIndex.toString();
            $$(item).attr("id", "xhz-" + id);

            qualities.push({
                id: id,
                name: hzName,
                isVip: isVip,
                isCurrent: isCurrent,
                action: `$$("#xhz-${id}").click();`,
                level: _tvFunc.hzLevel(hzName, 1)
            });
            newIndex++;
        });

        return qualities;
    },

    async fetchEpisodes() {
        return new Promise((resolve) => {
            _tvFunc.check(function() { return __playinfo__; }, function() {
                let nowId = __playinfo__.result.supplement.ogv_episode_info.episode_id;
                let vodId = __playinfo__.result.supplement.ogv_season_info.season_id;

                if (!nowId) {
                    resolve([]);
                    return;
                }

                let requestUrl = `https://api.bilibili.com/pgc/view/web/ep/list?ep_id=${nowId}`;
                _apiX.getJson(requestUrl, {
                    "User-Agent": _apiX.userAgent(false),
                    "tv-ref": "https://www.bilibili.com/"
                }, function(text) {
                    try {
                        let data = JSON.parse(text);
                        if (!data.result || !data.result.episodes) {
                            resolve([]);
                            return;
                        }

                        // 使用 Map 去重（按 title 去重）
                        let orderMap = new Map();
                        $$.each(data.result.episodes, function(index, item) {
                            orderMap.set(item.title, item);
                        });

                        let allEpisodes = [];
                        orderMap.forEach((item, k) => {
                            let title = _tvFunc.title(item.title);
                            let isVip = item.badge === "会员";
                            let id = item.ep_id.toString();
                            let url = `https://www.bilibili.com/bangumi/play/ep${id}`;
                            let remark = item.badge || "";

                            allEpisodes.push({
                                vodId: vodId.toString(),
                                id: id,
                                url: url,
                                isVip: isVip,
                                name: item.show_title,
                                remark: remark,
                                title: title,
                                site: "bili"
                            });
                        });

                        resolve(allEpisodes);
                    } catch (e) {
                        console.error("Parse episodes error", e);
                        resolve([]);
                    }
                }, function() {
                    resolve([]);
                });
            });
        });
    }
};

// 初始化
$$(function() {
    _tvFunc.check(function() { return __playinfo__; }, function() {
        let isLogin = __playinfo__.result.user_status.is_login;
        console.log("isLogin:: " + isLogin);
        _data.init();
    });
});

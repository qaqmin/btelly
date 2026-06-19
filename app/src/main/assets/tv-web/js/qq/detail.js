/**
 * 从 URL 中提取 cid 和 vid
 * URL 格式：https://v.qq.com/x/cover/{cid}/{vid}.html
 */
function getQQVideoParams() {
    const url = location.href;
    // 匹配 /x/cover/{cid}/{vid}.html
    const match = url.match(/\/x\/cover\/([^\/]+)\/([^\/]+)\.html/);
    if (match) {
        return {
            cid: match[1],
            vid: match[2]
        };
    }
    return null;
}

const _ctrlx = {
    toggleFullScreen() {
        _tvFunc.check(
            () => $$(".txp_btn_fake").length > 0,
            () => {
                $$(".txp_btn_fake").click();
            }
        );
    },
    /** 开关弹幕：直接点击弹幕按钮或切换弹幕层显示（参考 qq_plugin，不监听 d 键） */
    toggleDanmaku() {
        let btn = $$(".barrage-switch");
        if (!btn.length) {
            btn = $$(".txp_btn_barrage, .txp_btn_barrage_on, .txp_btn_barrage_off");
        }
        if (btn.length) {
            btn.first().click();
            return;
        }
        const layer = $$(".barrage-control-v2, .txp_barrage_layer");
        if (layer.length) {
            const el = layer[0];
            const isHidden = el.style.display === "none" || $$(el).hasClass("txp_none");
            if (isHidden) {
                el.style.display = "";
                $$(el).removeClass("txp_none");
            } else {
                el.style.display = "none";
                $$(el).addClass("txp_none");
            }
        }
    },
    /**
     * 发送弹幕：填写输入框并点击发表（参考 qq_plugin，用 Zepto 简化）
     * @param {string} text - 弹幕内容
     * @returns {boolean} 是否执行成功
     */
    sendDanmu(text) {
        if (typeof text !== "string" || !text.trim()) {
            console.warn("[qq/detail] 弹幕内容不能为空");
            return false;
        }
        const container = $$(".barrage-input, .thumbplayer .barrage-input").first();
        if (!container.length) {
            console.warn("[qq/detail] 未找到弹幕输入容器");
            return false;
        }
        const input = container.find("input[type='text'], input").first();
        if (!input.length) {
            console.warn("[qq/detail] 未找到弹幕输入框");
            return false;
        }
        const switchBtn = $$(".barrage-switch");
        if (switchBtn.length) {
            const title = (switchBtn.attr("title") || "").toLowerCase();
            const isOpen = title.indexOf("关闭") >= 0 || title.indexOf("已开启") >= 0 || switchBtn.hasClass("active") || switchBtn.hasClass("on");
            if (!isOpen) {
                switchBtn.first().click();
            }
        }
        const txt = text.trim();
        input.focus();
        input.val(txt);
        input[0].dispatchEvent(new Event("input", { bubbles: true }));
        input[0].dispatchEvent(new Event("change", { bubbles: true }));
        const submit = container.find(".submit-btn, button[type='button'], button").first();
        if (!submit.length) {
            console.warn("[qq/detail] 未找到发表按钮");
            return false;
        }
        setTimeout(() => {
            submit.click();
        }, 100);
        return true;
    },
    showLogin() {
        $$(".btn_pop_link").click();
    },
    hideLogin() {
        $$("[class^='main-login-wnd-module_close-button']").trigger("click");
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
         // 各个数据独立异步获取并发送，互不阻塞
         this.sendLoginStatus();
         this.sendDanmakuSupport();
         this.sendEpisodes();
        // 等待画质按钮加载（腾讯视频可能有90秒广告，设置120秒超时）
        await new Promise(resolve =>
            _tvFunc.check(() => $$(".txp_btn_definition").find(".txp_menuitem").length > 0, resolve, 1000, 120)
        );

        // 全屏
        _ctrlx.toggleFullScreen();
        // 音量100
        _tvFunc.volume100();
        this.sendVideoState();
        this.sendQualities();
    
    },

    /** 上报当前页是否支持弹幕开关、发送弹幕（App 据此决定是否展示弹幕按钮） */
    sendDanmakuSupport() {
        _apiX.postMessage({
            type: "danmakuSupport",
            data: {
                supportsDanmakuToggle: true,
                supportsSendDanmu: true
            }
        });
    },

    async sendLoginStatus() {
        try {
            _tvFunc.check(
                () => typeof txv !== 'undefined',
                () => {
                    const isLogin = txv.login.isLogin();
                        _apiX.postMessage({
                            type: 'loginStatus',
                            data: { isLogin }
                        });
                }
            );
        } catch (e) {
            console.error("Send loginStatus error", e);
        }
    },

    async sendVideoState() {
        _tvFunc.check(
            () => {
                let video = _tvFunc.getVideo();
                return video && video.readyState >= 1;
            },
            () => {
                _tvController.videoState();

                if (window._videoStateInterval) {
                    clearInterval(window._videoStateInterval);
                }
                window._videoStateInterval = setInterval(() => {
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

    async sendEpisodes() {
        try {
            const episodes = await this.fetchEpisodes();
            if (episodes && episodes.length > 0) {
                // 从 URL 获取当前 vid
                const params = getQQVideoParams();
                let currentEpisode = null;
                if (params && params.vid) {
                    currentEpisode = episodes.find(e => e.id === params.vid);
                }

                _apiX.postMessage({
                    type: 'episodes',
                    data: { episodes, currentEpisode }
                });
            }
        } catch (e) {
            console.error("Send episodes error", e);
        }
    },

    fetchQualities() {
        let qualities = [];
        let newIndex = 0;

        $$(".txp_btn_definition").find(".txp_menuitem").each(function(index, item) {
            let hzName = $$(item).attr("data-payload");
            if (!hzName || $$(item).text().includes("客户端")) {
                return true;
            }

            let isCurrent = $$(item).hasClass("txp_current");
            let isVip = $$(item).text().includes("VIP");
            let id = newIndex.toString();
            $$(item).attr("id", "xhz-" + id);

            qualities.push({
                id: id,
                name: hzName,
                isVip: isVip,
                isCurrent: isCurrent,
                action: `$$("[data-payload='${hzName}']").click();`,
                //`$$("#xhz-${id}").click();`,
                level: _tvFunc.hzLevel(hzName, 1)
            });
            newIndex++;
        });

        return qualities;
    },

    async fetchEpisodes() {
        return new Promise((resolve) => {
            // 从 URL 获取 cid
            const params = getQQVideoParams();
            if (!params || !params.cid) {
                console.warn("[fetchEpisodes] 无法从 URL 获取 cid");
                resolve([]);
                return;
            }

            let vodId = params.cid;

            let requestUrl = "https://pbaccess.video.qq.com/trpc.universal_backend_service.page_server_rpc.PageServer/GetPageData?vplatform=2&vversion_name=8.2.96";
            _apiX.postJson(
                requestUrl,
                { "User-Agent": _tvFunc.userAgent(true), "tv-ref": "https://m.v.qq.com/" },
                {
                    "page_params": {
                        "req_from": "web_mobile",
                        "page_id": "vsite_episode_list",
                        "page_type": "detail_operation",
                        "id_type": "1",
                        "page_size": "100",
                        "cid": vodId,
                        "detail_page_type": "1"
                    },
                    "has_cache": 1
                },
                function(text) {
                    console.log("fetchEpisodes", text);
                    try {
                        let data = JSON.parse(text);
                        if (data.ret !== 0 || !data.data.module_list_datas) {
                            resolve([]);
                            return;
                        }

                        let vodItems = data.data.module_list_datas[0].module_datas[0].item_data_lists.item_datas;
                        let orderMap = new Map();
                        $$.each(vodItems, function(i, value) {
                            let item = value.item_params;
                            orderMap.set(item.title, item);
                        });

                        let allEpisodes = [];
                        orderMap.forEach((item) => {
                            let remark = "";
                            let isVip = false;
                            if (item.uni_imgtag) {
                                let imgTag = JSON.parse(item.uni_imgtag);
                                let text = imgTag.tag_2.text;
                                if (text !== "") remark = text;
                                if (text === 'VIP') isVip = true;
                            }

                            let title = _tvFunc.title(item.title);
                            if (title.includes("采访") || title.includes("彩蛋")) {
                                title = item.title;
                            }

                            allEpisodes.push({
                                vodId: item.cid,
                                id: item.vid,
                                url: `https://v.qq.com/x/cover/${item.cid}/${item.vid}.html`,
                                isVip: isVip,
                                name: item.play_title,
                                remark: remark,
                                title: title,
                                site: "qq"
                            });
                        });

                        resolve(allEpisodes);
                    } catch (e) {
                        console.error("Parse episodes error", e);
                        resolve([]);
                    }
                },
                function() {
                    resolve([]);
                }
            );
        });
    }
};

// 初始化 - 直接调用，与 bili/youku/mgtv 一致
$$(function() {
    _data.init();
});

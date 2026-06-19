const _ctrlx = {
    toggleFullScreen() {
        $$("#webfullscreen-icon").click();
    },
    showLogin() {
        $$(".crmusercenter_avatar img").click();
    },
    hideLogin() {
        $$(".loginnew_close").click();
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
        _tvFunc.check(() => $$("#webfullscreen-icon").length > 0, function (){
            _ctrlx.toggleFullScreen();
        })
    },

    async collectAllData() {
        // 各个数据独立异步获取并发送，互不阻塞
        this.sendLoginStatus();
        // 等待画质按钮加载
        await new Promise(resolve => 
            _tvFunc.check(() => $$(".kui-quality-quality-item").length > 0, resolve)
        );
        this.sendVideoState();
        this.sendQualities();
    },

    async sendLoginStatus() {
        try {
            const isLogin = $$(".usercenter_vipborder").length > 0 || $$(".crmusercenter_user_center_box").find("img").attr("title") !== "";

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
            _tvFunc.check(
                function() {
                    return $$(".kui-quality-quality-item").length > 0 && $$("[com='quality']").find(".control-label").length > 0;
                },
                function() {
                    let qualities = [];
                    let hzNow = $$("[com='quality']").find(".control-label").text();
                    let newIndex = 0;

                    $$(".kui-quality-quality-item").each(function(index, item) {
                        let hzName = $$(item).text();
                        if (hzName.includes("客户端")) return;

                        let isVip = false;
                        if (hzName.includes("VIP")) {
                            isVip = true;
                            hzName = hzName.replace("VIP", "");
                        }

                        let id = newIndex.toString();
                        $$(item).attr("id", "xhz-" + id);

                        qualities.push({
                            id: id,
                            name: hzName,
                            isVip: isVip,
                            isCurrent: hzName.includes(hzNow),
                            action: `$$("#xhz-${id}").click();`,
                            level: _tvFunc.hzLevel(hzName, 1)
                        });
                        newIndex++;
                    });

                    // 添加语言选项（如果有）
                    if ($$(".kui-language-language-container").find(".control-item").length > 1) {
                        $$(".kui-language-language-container").find(".control-item").each(function(index, item) {
                            let hzName = $$(item).text();
                            let id = newIndex.toString();
                            $$(item).attr("id", "xhz-" + id);
                            qualities.push({
                                id: id,
                                name: hzName,
                                isVip: false,
                                isCurrent: false,
                                action: `$$("#xhz-${id}").click();`,
                                level: null
                            });
                            newIndex++;
                        });
                    }

                    resolve(qualities);
                }
            );
        });
    }
};

$$(function(){
    
     _data.init();
});


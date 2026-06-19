const _ctrlx = {
    toggleFullScreen() {
        $$('[class*="player-buttons_videofullBtn"]').click();
        $$('[class*="player-buttons_quitvideofullBtn"]').click();
    },
    showLogin() {
        $$("#btn_user").click();
    },
    hideLogin() {
        // 关闭登录弹窗
        $$('[class*="simple-buttons_close_btn"]').click();
    },
    async hz(level){
        $$("[class*='XPlayer_bottom']").css({
            'transform': 'translateY(0)',
            'opacity': '1'
        });
        $$("[class*='player-buttons_resBtn']").mouseover();
        await new Promise(resolve =>
            _tvFunc.check(() => $$("[class*='bitstream-item_bsItem']").length > 0, resolve)
        );
        $$("[class*='bitstream-item_bsItem']").filter(function() {
            return $$(this).text().indexOf(level) !== -1;
        }).click();
        $$("[class*='XPlayer_bottom']").css({
            'transform': 'translateY(0)',
            'opacity': '0'
        });
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
        this.sendVideoState();
        this.sendQualities();
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
            setTimeout(() =>{
                this.fetchQualities();
            },500);

        } catch (e) {
            console.error("Send qualities error", e);
        }
    },
    async fetchQualities() {
        // 先触发 hover 显示画质菜单
        $$("[class*='player-buttons_resBtn']").mouseover();
        _tvFunc.check(() => $$("[class*='bitstream-item_bsItem']").length > 0, function (){
            // 遍历画质选项（跳过第一个"客户端最高4k"）
            let newIndex = 0;
            let qualities = [];
            $$("[class*='bitstream-item_bsItem']").each(function(index, item) {
                let $item = $$(item);
                let titleText = $item.find("[class*='bitstream-item_title']").text().trim();

                // 跳过"客户端"选项
                if (!titleText || titleText.includes("客户端")) {
                    return true; // continue
                }

                let id = newIndex.toString();
                //$item.attr("id", "xhz-" + id);

                // 检查是否是 VIP
                let isVip = $item.hasClass("bitstream-item_vip__udaUl") ||
                    $item.find("[class*='bitstream-item_vip']").length > 0 ||
                    $item.find("[class*='vipIcon']").length > 0;

                // 检查是否是当前选中
                let isCurrent = $item.attr("class").indexOf("selected") !== -1;

                qualities.push({
                    id: id,
                    name: titleText,
                    isVip: isVip,
                    isCurrent: isCurrent,
                    action: `_ctrlx.hz("${titleText}")`,
                    level: _tvFunc.hzLevel(titleText, 1)
                });
                newIndex++;
            });
            if (qualities && qualities.length > 0) {
                _apiX.postMessage({
                    type: 'qualities',
                    data: { qualities }
                });
            }
            $$("[class*='XPlayer_bottom']").css({
                'transform': 'translateY(0)',
                'opacity': '0'
            });
        });
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

const _ctrlx={
    toggleFullScreen() {
        // 调用 CSS 全屏方案
        _tvFunc.webFullScreen($$("#_video_player"));
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
        this.hideElements();
        this.collectAllData();
    },

    hideElements() {
        // 隐藏多余元素
        $$(".nav_wrapper_bg").hide();
        $$(".header_nav").hide();
        $$(".retrieve").hide();
        $$(".kj").hide();
        $$(".gwA18043_ind01").hide();
        
        // 隐藏弹窗
        _tvFunc.check(function(){return $$(".XUQIU18897_fuceng").length>0;}, function(){
            $$(".XUQIU18897_fuceng").hide();
        });
    },

    async collectAllData() {
        this.sendEpisodes();
        // 等待基础元素加载
        await new Promise(resolve => 
            _tvFunc.check(() => $$(".vjs-fullscreen-control").length > 0, resolve)
        );
        _ctrlx.toggleFullScreen();
        // 各个数据独立异步获取并发送，互不阻塞
        this.sendLoginStatus();
        this.sendVideoState();
        this.sendQualities();

    },

   async sendLoginStatus() {
        // CCTV 暂无登录状态检测，不发送
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
                let currentEpisode = null;
                if (typeof itemid1 !== 'undefined') {
                    currentEpisode = episodes.find(e => e.id === itemid1);
                }

                _apiX.postMessage({
                    type: 'episodes',
                    data: {
                        episodes,
                        currentEpisode
                    }
                });
            }
        } catch (e) {
            console.error("Send episodes error", e);
        }
    },

    fetchQualities() {
        let qualities = [];
        let hzNow = $$(".vjs-playback-quality-value").text();

        $$(".vjs-playback-quality").find(".vjs-menu-item").each(function(index, item) {
            let id = index.toString();
            let hzName = $$(item).find(".vjs-menu-item-text").text();
            $$(item).attr("id", "xhz-" + id);

            qualities.push({
                id: id,
                name: hzName,
                isVip: false,
                isCurrent: hzNow === hzName,
                action:`$$("#xhz-${id}").click();`,
                level: _tvFunc.hzLevel(hzName, 2)
            });
        });
        return qualities;
    },

    fetchEpisodes() {
        return new Promise((resolve) => {
            // 如果没有 next_id，先获取
            if (typeof next_id === 'undefined') {
                this.fetchAlbumId((albumId) => {
                    this.fetchEpisodesList(albumId, resolve);
                });
            } else {
                this.fetchEpisodesList(next_id, resolve);
            }
        });
    },

    fetchAlbumId(callback) {
        _apiX.getJson(
            `https://api.cntv.cn/NewVideoset/getVideoAlbumInfoByVideoId?id=${itemid1}&serviceId=tvcctv`,
            { "User-Agent": _apiX.userAgent(false), "tv-ref": "https://tv.cctv.com/" },
            function(text) {
                try {
                    let data = JSON.parse(text);
                    window.next_id = data.data.id;
                    callback(data.data.id);
                } catch (e) {
                    console.error("Parse album id error", e);
                    callback(null);
                }
            },
            function() {
                callback(null);
            }
        );
    },

    fetchEpisodesList(albumId, resolve) {
        if (!albumId) {
            resolve([]);
            return;
        }

        let mode = 0;
        if ($$("script[src*='index_dhp.js']").length > 0) {
            mode = 1;
        }

        let requestUrl = `https://api.cntv.cn/NewVideo/getVideoListByAlbumIdNew?id=${albumId}&serviceId=tvcctv&pub=1&mode=${mode}&part=0&n=100&sort=asc`;
        
        _apiX.getJson(
            requestUrl,
            { "User-Agent": _apiX.userAgent(false), "tv-ref": "https://tv.cctv.com/" },
            function(text) {
                try {
                    let data = JSON.parse(text);
                    if (!data.data || !data.data.list || data.data.list.length === 0) {
                        resolve([]);
                        return;
                    }

                    let episodes = [];
                    data.data.list.forEach((item, index) => {
                        let title = `第${index + 1}集`;
                        episodes.push({
                            vodId: albumId,
                            id: item.id,
                            url: item.url,
                            isVip: false,
                            name: item.title,
                            remark: "",
                            title: title,
                            site: "cctv"
                        });
                    });
                    resolve(episodes);
                } catch (e) {
                    console.error("Parse episodes error", e);
                    resolve([]);
                }
            },
            function() {
                resolve([]);
            }
        );
    }
};

(function(){
    $$(function(){
        // 检查是否需要跳转
        _tvFunc.check(function(){return $$(".ljgk").length>0;}, function(){
            let link = $$(".ljgk").find("a").attr("href");
            window.location.href = link;
        });
        
        // 初始化数据
        _data.init();
    });
})();

// 分享卡片生成器
const ShareCard = {
    // 单部番剧卡片
    async animeCard(anime) {
        const dpr = window.devicePixelRatio || 1;
        const w = 400, h = 560;
        const canvas = document.createElement('canvas');
        canvas.width = w * dpr;
        canvas.height = h * dpr;
        canvas.style.width = w + 'px';
        canvas.style.height = h + 'px';
        const ctx = canvas.getContext('2d');
        ctx.scale(dpr, dpr);

        // 背景
        const gradient = ctx.createLinearGradient(0, 0, 400, 560);
        gradient.addColorStop(0, '#1a1a2e');
        gradient.addColorStop(1, '#16213e');
        ctx.fillStyle = gradient;
        ctx.fillRect(0, 0, 400, 560);

        // 封面图
        if (anime.coverUrl) {
            try {
                const img = await this.loadImage(anime.coverUrl);
                ctx.save();
                this.roundRect(ctx, 24, 24, 140, 200, 12);
                ctx.clip();
                ctx.drawImage(img, 24, 24, 140, 200);
                ctx.restore();
            } catch(e) {}
        }

        // 标题
        ctx.fillStyle = '#ffffff';
        ctx.font = 'bold 20px "Noto Serif SC", serif';
        this.wrapText(ctx, anime.name, 180, 50, 190, 26);

        // 评分
        if (anime.score) {
            ctx.fillStyle = '#f0c040';
            ctx.font = 'bold 32px sans-serif';
            ctx.fillText(anime.score.toFixed(1), 180, 110);
            ctx.fillStyle = '#888';
            ctx.font = '14px sans-serif';
            ctx.fillText('/ 10', 240, 110);
        }

        // 信息
        ctx.fillStyle = '#aaa';
        ctx.font = '14px sans-serif';
        const statusDisplay = { watching: '追中', finished: '已完成', planning: '计划', dropped: '放弃' };
        const info = [
            anime.season || '',
            (statusDisplay[anime.status] || anime.status) + ' · ' + anime.currentEpisode + '/' + anime.totalEpisodes + ' 集',
        ].filter(Boolean);
        info.forEach((line, i) => ctx.fillText(line, 180, 140 + i * 22));

        // 进度条
        const pct = anime.totalEpisodes > 0 ? anime.currentEpisode / anime.totalEpisodes : 0;
        ctx.fillStyle = '#333';
        this.roundRect(ctx, 180, 190, 190, 8, 4);
        ctx.fill();
        ctx.fillStyle = '#4a6ad0';
        this.roundRect(ctx, 180, 190, 190 * pct, 8, 4);
        ctx.fill();
        ctx.fillStyle = '#888';
        ctx.font = '12px sans-serif';
        ctx.fillText(Math.round(pct * 100) + '%', 375, 198);

        // 标签
        if (anime.tags) {
            const tags = anime.tags.split(',').slice(0, 4).map(t => t.trim());
            let x = 24;
            ctx.font = '12px sans-serif';
            tags.forEach(tag => {
                const w = ctx.measureText(tag).width + 16;
                ctx.fillStyle = 'rgba(74,106,208,0.3)';
                this.roundRect(ctx, x, 240, w, 24, 12);
                ctx.fill();
                ctx.fillStyle = '#8aa';
                ctx.fillText(tag, x + 8, 256);
                x += w + 8;
            });
        }

        // 备注
        if (anime.remark) {
            ctx.fillStyle = '#666';
            ctx.font = '13px sans-serif';
            this.wrapText(ctx, anime.remark.substring(0, 80), 24, 290, 352, 18);
        }

        // 底部分隔线
        ctx.strokeStyle = '#333';
        ctx.beginPath();
        ctx.moveTo(24, 500);
        ctx.lineTo(376, 500);
        ctx.stroke();

        // 水印
        ctx.fillStyle = '#555';
        ctx.font = '12px sans-serif';
        ctx.fillText('OtakuLog · 追番日记', 24, 530);
        ctx.fillText(new Date().toLocaleDateString('zh-CN'), 300, 530);

        return canvas.toDataURL('image/png');
    },

    // 追番总结卡片
    async summaryCard(stats, topAnime) {
        const dpr = window.devicePixelRatio || 1;
        const w = 480, h = 640;
        const canvas = document.createElement('canvas');
        canvas.width = w * dpr;
        canvas.height = h * dpr;
        canvas.style.width = w + 'px';
        canvas.style.height = h + 'px';
        const ctx = canvas.getContext('2d');
        ctx.scale(dpr, dpr);

        // 背景
        const gradient = ctx.createLinearGradient(0, 0, 480, 640);
        gradient.addColorStop(0, '#0f0c29');
        gradient.addColorStop(0.5, '#302b63');
        gradient.addColorStop(1, '#24243e');
        ctx.fillStyle = gradient;
        ctx.fillRect(0, 0, 480, 640);

        // 标题
        ctx.fillStyle = '#ffffff';
        ctx.font = 'bold 24px "Noto Serif SC", serif';
        ctx.fillText('追番总结', 24, 50);
        ctx.fillStyle = '#888';
        ctx.font = '13px sans-serif';
        ctx.fillText(new Date().toLocaleDateString('zh-CN'), 24, 72);

        // 统计卡片
        const cards = [
            { label: '追番数', value: String(stats.total || 0), color: '#4a6ad0' },
            { label: '平均分', value: (stats.averageScore || 0).toFixed(1), color: '#f0c040' },
            { label: '总集数', value: String(stats.totalEpisodes || 0), color: '#4a8a6a' },
        ];
        cards.forEach((c, i) => {
            const x = 24 + i * 150;
            ctx.fillStyle = 'rgba(255,255,255,0.08)';
            this.roundRect(ctx, x, 95, 138, 80, 10);
            ctx.fill();
            ctx.fillStyle = c.color;
            ctx.font = 'bold 28px sans-serif';
            ctx.fillText(c.value, x + 16, 135);
            ctx.fillStyle = '#aaa';
            ctx.font = '12px sans-serif';
            ctx.fillText(c.label, x + 16, 158);
        });

        // TOP 番剧
        ctx.fillStyle = '#fff';
        ctx.font = 'bold 16px "Noto Serif SC", serif';
        ctx.fillText('最高评分', 24, 220);

        if (topAnime && topAnime.length > 0) {
            for (let i = 0; i < Math.min(topAnime.length, 5); i++) {
                const a = topAnime[i];
                const y = 240 + i * 44;
                // 排名
                ctx.fillStyle = i < 3 ? '#f0c040' : '#666';
                ctx.font = 'bold 18px sans-serif';
                ctx.fillText('#' + (i + 1), 24, y + 20);

                // 封面缩略图
                if (a.coverUrl) {
                    try {
                        const img = await this.loadImage(a.coverUrl);
                        ctx.save();
                        this.roundRect(ctx, 60, y, 32, 32, 4);
                        ctx.clip();
                        ctx.drawImage(img, 60, y, 32, 32);
                        ctx.restore();
                    } catch(e) {}
                }

                // 名称和评分
                ctx.fillStyle = '#fff';
                ctx.font = '14px sans-serif';
                ctx.fillText(a.name.length > 12 ? a.name.substring(0, 12) + '...' : a.name, 100, y + 14);
                ctx.fillStyle = '#f0c040';
                ctx.font = '13px sans-serif';
                ctx.fillText((a.score || 0).toFixed(1) + ' / 10', 100, y + 30);
                ctx.fillStyle = '#888';
                ctx.fillText(a.season || '', 160, y + 30);
            }
        }

        // 底部
        ctx.strokeStyle = '#333';
        ctx.beginPath();
        ctx.moveTo(24, 590);
        ctx.lineTo(456, 590);
        ctx.stroke();
        ctx.fillStyle = '#555';
        ctx.font = '12px sans-serif';
        ctx.fillText('OtakuLog · 追番日记', 24, 618);

        return canvas.toDataURL('image/png');
    },

    // 工具函数
    loadImage(url) {
        return new Promise((resolve, reject) => {
            const img = new Image();
            img.crossOrigin = 'anonymous';
            img.onload = () => resolve(img);
            img.onerror = reject;
            img.src = url;
        });
    },

    roundRect(ctx, x, y, w, h, r) {
        ctx.beginPath();
        ctx.moveTo(x + r, y);
        ctx.lineTo(x + w - r, y);
        ctx.quadraticCurveTo(x + w, y, x + w, y + r);
        ctx.lineTo(x + w, y + h - r);
        ctx.quadraticCurveTo(x + w, y + h, x + w - r, y + h);
        ctx.lineTo(x + r, y + h);
        ctx.quadraticCurveTo(x, y + h, x, y + h - r);
        ctx.lineTo(x, y + r);
        ctx.quadraticCurveTo(x, y, x + r, y);
        ctx.closePath();
    },

    wrapText(ctx, text, x, y, maxWidth, lineHeight) {
        let line = '';
        for (let i = 0; i < text.length; i++) {
            const testLine = line + text[i];
            if (ctx.measureText(testLine).width > maxWidth && line) {
                ctx.fillText(line, x, y);
                line = text[i];
                y += lineHeight;
            } else {
                line = testLine;
            }
        }
        ctx.fillText(line, x, y);
    },

    // 下载图片
    download(dataUrl, filename) {
        const link = document.createElement('a');
        link.download = filename;
        link.href = dataUrl;
        link.click();
    }
};

// B视机（BTelly）— by QAQMin (qaqmin09577)
package com.btelly.core.common

import android.util.Base64
import com.btelly.BuildConfig

/**
 * 项目基因标识：作者与开源仓库的唯一事实来源。
 *
 * 分三处消费：
 *  - 显性：欢迎页脚、设置→关于本机的作者/仓库行（用户可见）；
 *  - 半显性：BuildConfig 字段、Manifest meta-data、日志横幅（跟着 APK 走，改包名也洗不掉）；
 *  - 隐性：BASE64 / 拆分片段 / 规范哈希三种编码水印，源码静态 grep 不到 qaqmin，
 *    运行期解码还原后写入日志横幅，反编译动态调试可见。
 */
object BrandGene {

    /** 作者 ID（GitHub / Codeberg 通用账号名）。 */
    const val AUTHOR_ID: String = "qaqmin09577"

    /** 作者昵称（小写形式即 qaqmin）。 */
    const val AUTHOR_NAME: String = "QAQMin"

    /** 主仓库（GitHub，新账号）与镜像仓库（Codeberg，旧账号备用渠道）。 */
    const val GITHUB_REPO_URL: String = "https://github.com/qaqmin09577/btelly"
    const val CODEBERG_REPO_URL: String = "https://codeberg.org/min09577/btelly"

    /** 兼容旧字段：默认指向主仓库（GitHub）。 */
    val REPO_URL: String = GITHUB_REPO_URL

    /** 完整署名，日志横幅与关于页共用。 */
    val SIGNATURE: String = "B视机 (BTelly) — by $AUTHOR_NAME ($AUTHOR_ID)"

    /** 隐藏哨兵：嵌入字符串常量的水印，反编译 grep 可见。 */
    const val WATERMARK: String =
        "BTelly::qaqmin09577::QAQMin::btelly-brand-gene-v2"

    // ==================== 隐性水印 ====================
    // 不明文包含 qaqmin：以下三种编码各存一份，运行期还原。
    // R8 保活：proguard-rules.pro 已 keep com.btelly.core.common.**，
    // 且 AppLog 启动横幅会调用它们，不会被当死代码清掉。

    /** base64("BTelly::qaqmin09577::hidden-gene-v2")，反编译静态不可读。 */
    private const val HIDDEN_GENE_B64 =
        "QlRlbGx5OjpxYXFtaW4wOTU3Nzo6aGlkZGVuLWdlbmUtdjI="

    /** 拆分片段：拼接后即 "qaqmin"，单看任一片段都无意。 */
    private const val GENE_FRAGMENT_HEAD = "qa"
    private const val GENE_FRAGMENT_TAIL = "qmin"

    /** "qaqmin".hashCode()，Java String hashCode 为规范算法，跨运行时稳定。 */
    private const val GENE_HASH = -966817199

    /** 解码还原的隐性水印。 */
    fun hiddenGene(): String =
        String(Base64.decode(HIDDEN_GENE_B64, Base64.DEFAULT), Charsets.UTF_8)

    /** 拆分还原的作者标识小写形态。 */
    val splitGene: String
        get() = GENE_FRAGMENT_HEAD + GENE_FRAGMENT_TAIL

    /** 自检：输入字符串是否命中基因哈希。 */
    fun matchesGene(input: String): Boolean = input.hashCode() == GENE_HASH

    /**
     * 与 build.gradle.kts 的 buildConfigField 互为备份的读取位。
     * 两个注入点（本文件常量 + BuildConfig 字段）互相独立存活；
     * 本 val 同时承担「引用 BuildConfig 字段」的职责，防止 R8 把字段当死代码删掉。
     */
    val BUILD_FIELD_TAG: String =
        "BuildConfig[${BuildConfig.BT_AUTHOR_NAME}/${BuildConfig.BT_AUTHOR_ID}]"
}

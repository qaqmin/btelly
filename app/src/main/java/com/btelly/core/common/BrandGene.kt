// B视机（BTelly）— by QAQMin (min09577)
package com.btelly.core.common

import com.btelly.BuildConfig

/**
 * 项目基因标识：作者与开源仓库的唯一事实来源。
 *
 * 分两处消费：
 *  - 显眼：欢迎页脚、设置→关于本机的作者/仓库行（用户可见）；
 *  - 隐蔽：BuildConfig 字段、Manifest meta-data、日志横幅（跟着 APK 走，改包名也洗不掉）。
 */
object BrandGene {

    /** 作者 ID（codeberg / github 同账号名）。 */
    const val AUTHOR_ID: String = "min09577"

    /** 作者昵称。 */
    const val AUTHOR_NAME: String = "QAQMin"

    /** 项目开源仓库地址（GitHub 账号解封后将同步更新）。 */
    const val REPO_URL: String = "https://codeberg.org/min09577/btelly"

    /** 完整署名，日志横幅与关于页共用。 */
    val SIGNATURE: String = "B视机 (BTelly) — by $AUTHOR_NAME ($AUTHOR_ID)"

    /** 隐藏哨兵：嵌入字符串常量的水印，反编译 grep 可见。 */
    const val WATERMARK: String =
        "BTelly::min09577::QAQMin::taile2c032::btelly-brand-gene-v1"

    /**
     * 与 build.gradle.kts 的 buildConfigField 互为备份的读取位。
     * 两个注入点（本文件常量 + BuildConfig 字段）互相独立存活；
     * 本 val 同时承担「引用 BuildConfig 字段」的职责，防止 R8 把字段当死代码删掉。
     */
    val BUILD_FIELD_TAG: String =
        "BuildConfig[${BuildConfig.BT_AUTHOR_NAME}/${BuildConfig.BT_AUTHOR_ID}]"
}


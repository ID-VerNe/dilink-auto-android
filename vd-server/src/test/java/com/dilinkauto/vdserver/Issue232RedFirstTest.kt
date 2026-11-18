package com.dilinkauto.vdserver

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * issue #232 的红基线：这两条断言在**未打补丁的 base** 上必须先红。
 *
 * 夹具是**真实** dump（`debug-logs/dpi-repro-20261103-213130/activities-with-baidumap-on-vd.txt`
 * 的 task 17 块，逐字摘录；该目录在 .gitignore 里，不能按路径读文件，只能内嵌）——
 * task 17 当时还挂在 Display #19（400dpi 的 VD）上，正是 #48 要抓的「残留 VD 配置」形态：
 *  - 块内首个 `NNNdpi` 是 ActivityRecord 的 `compat={560dpi}`（假绿来源），
 *    而任务真实配置（`CurrentConfiguration` / `mOverrideConfig` / `mGlobalConfig`）是 400dpi；
 *  - bounds 的真实形态是 WinConfiguration 里的 `mBounds=Rect(0, 0 - 1080, 2152)`，
 *    旧正则只认 `[...]` / `(...)` 两种等号后紧跟括号的写法 → 0 命中 → `bounds=<unknown>`。
 *
 * base 上两条断言的失败形态与 issue 症状一致：
 *  - [ActivityDumpParser.extractBounds] 返回 null（0 命中）；
 *  - [ActivityDumpParser.extractDensity] 兜底 `(\d+)dpi` 先抓到 `compat={560dpi}`，
 *    返回 "560" 而不是 "400"（假绿）。
 */
class Issue232RedFirstTest {

    /** 真实 dump 的 task 17 块（Display #19 = 400dpi VD，task 块本身即 taskBlock 的输出形态）。 */
    private val task17OnVd = """
  * Task{ad54f40 #17 type=standard A=10001:com.baidu.BaiduMap U=0 visible=true visibleRequested=true mode=fullscreen translucent=false sz=1}
    mLastNonFullscreenBounds=Rect(269, 536 - 811, 1616)
    isSleeping=false
    topResumedActivity=ActivityRecord{102f887 u0 com.baidu.BaiduMap/com.baidu.baidumaps.MapsActivity t17}
    * Hist  #0: ActivityRecord{102f887 u0 com.baidu.BaiduMap/com.baidu.baidumaps.MapsActivity t17}
      packageName=com.baidu.BaiduMap processName=com.baidu.BaiduMap
      launchedFromUid=10001 launchedFromPackage=com.baidu.BaiduMap launchedFromFeature=null userId=0
      app=ProcessRecord{487f1ca 27234:com.baidu.BaiduMap/u0a1}
      Intent { flg=0x10020000 cmp=com.baidu.BaiduMap/com.baidu.baidumaps.MapsActivity (has extras) }
      rootOfTask=true task=Task{ad54f40 #17 type=standard A=10001:com.baidu.BaiduMap}
      taskAffinity=10001:com.baidu.BaiduMap
      mActivityComponent=com.baidu.BaiduMap/com.baidu.baidumaps.MapsActivity
      baseDir=/data/app/~~A_zkkxks-BHzL-LjtLfN1g==/com.baidu.BaiduMap-QLwtf66j1s1Bd_b5-EGD0g==/base.apk
      dataDir=/data/user/0/com.baidu.BaiduMap
      stateNotNeeded=false componentSpecified=true mActivityType=standard
      compat={560dpi} theme=0x7f120168
      mLastReportedConfigurations:
        mGlobalConfig={1.0 460mcc2mnc [zh_CN] ldltr sw432dp w432dp h861dp 400dpi nrml long port -touch -keyb/v/h -nav/h winConfig={ mBounds=Rect(0, 0 - 1080, 2152) mAppBounds=Rect(0, 0 - 1080, 2152) mMaxBounds=Rect(0, 0 - 1080, 2152) mDisplayRotation=ROTATION_0 mWindowingMode=fullscreen mActivityType=undefined mAlwaysOnTop=undefined mRotation=ROTATION_0} as.5 s.3334 fontWeightAdjustment=163}
        mOverrideConfig={1.0 ?mcc0mnc ?localeList ?layoutDir sw432dp w432dp h861dp 400dpi nrml long port ?uimode ?night -touch -keyb/v/h -nav/h winConfig={ mBounds=Rect(0, 0 - 1080, 2152) mAppBounds=Rect(0, 0 - 1080, 2152) mMaxBounds=Rect(0, 0 - 1080, 2152) mDisplayRotation=ROTATION_0 mWindowingMode=fullscreen mActivityType=standard mAlwaysOnTop=undefined mRotation=ROTATION_0} s.60 ?fontWeightAdjustment}
      mLastReportedActivityWindowInfo=ActivityWindowInfo{isEmbedded=false, taskBounds=Rect(0, 0 - 1080, 2152), taskFragmentBounds=Rect(0, 0 - 1080, 2152)}
      CurrentConfiguration={1.0 460mcc2mnc [zh_CN] ldltr sw432dp w432dp h861dp 400dpi nrml long port -touch -keyb/v/h -nav/h winConfig={ mBounds=Rect(0, 0 - 1080, 2152) mAppBounds=Rect(0, 0 - 1080, 2152) mMaxBounds=Rect(0, 0 - 1080, 2152) mDisplayRotation=ROTATION_0 mWindowingMode=fullscreen mActivityType=standard mAlwaysOnTop=undefined mRotation=ROTATION_0} as.5 s.60 fontWeightAdjustment=163}
      RequestedOverrideConfiguration={1.0 ?mcc0mnc ?localeList ?layoutDir ?swdp ?wdp ?hdp ?density ?lsize ?long ?ldr ?wideColorGamut ?orien ?uimode ?night ?touch ?keyb/?/? ?nav/? winConfig={ mBounds=Rect(0, 0 - 0, 0) mAppBounds=null mMaxBounds=Rect(0, 0 - 0, 0) mDisplayRotation=undefined mWindowingMode=undefined mActivityType=undefined mAlwaysOnTop=undefined mRotation=undefined} ?fontWeightAdjustment}
      ResolvedOverrideConfiguration={1.0 ?mcc0mnc ?localeList ?layoutDir sw432dp w432dp h861dp ?density ?lsize ?long ?ldr ?wideColorGamut port ?uimode ?night ?touch ?keyb/?/? ?nav/? winConfig={ mBounds=Rect(0, 0 - 0, 0) mAppBounds=Rect(0, 0 - 1080, 2152) mMaxBounds=Rect(0, 0 - 0, 0) mDisplayRotation=undefined mWindowingMode=undefined mActivityType=undefined mAlwaysOnTop=undefined mRotation=undefined} s.60 ?fontWeightAdjustment}
"""

    @Test
    fun `extract bounds must read the real rect form instead of returning null`() {
        // issue #232 候选 A：真实 dump 的 task 块里 bounds 是
        // `mBounds=Rect(0, 0 - 1080, 2152)`（WinConfiguration 内）；注意块内
        // `mLastNonFullscreenBounds` / `taskBounds` 都是大写 B 的引用，不得误抓。
        assertEquals(
            "真实 dump 里 bounds 形态是 Rect(...)，旧正则 0 命中返回 null（bounds=<unknown> 的来源）",
            "Rect(0, 0 - 1080, 2152)",
            ActivityDumpParser.extractBounds(task17OnVd)
        )
    }

    @Test
    fun `extract density must read the task configuration instead of the compat fallback`() {
        // issue #232 候选 B：块内首个 `NNNdpi` 是 ActivityRecord 的 `compat={560dpi}`，
        // 但这个 400dpi VD 上的任务真实配置是 400 —— 必须定向读 Configuration 段，
        // 全块扫 `(\d+)dpi` 的兜底只会对 VD 残留假绿（报 560）。
        assertEquals(
            "400dpi VD 上的任务必须读到真实配置 400，而不是 compat 兜底的 560",
            "400",
            ActivityDumpParser.extractDensity(task17OnVd)
        )
    }
}

package com.dilinkauto.vdserver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * issue #238 的红基线：这些断言在**未打补丁的 base**（main @ 7bda2839）上必须先红。
 *
 * 夹具是**真实** dump（`debug-logs/dpi-repro-20261103-213130/activities-with-baidumap-on-vd.txt`
 * 的 task 17 块，逐字摘录；该目录在 .gitignore 里，不能按路径读文件，只能内嵌），
 * 块内同时存在两处容易混淆的形态：
 *  - ActivityRecord 的 screen-compat 标志行 `      compat={560dpi} theme=0x…`（真 compat）；
 *  - 各 Configuration 的 `winConfig={ … mWindowingMode=fullscreen … }`（窗口模式，不是 compat）。
 *
 * base 上的失败形态与 issue 症状一致：
 *  - [ActivityDumpParser.extractCompat] 首选 `mWindowingMode=`，恒返回 "fullscreen"，
 *    真 compat 的 "560dpi" 从未被读到；
 *  - 块内没有 compat 行时它仍会回落到 `mWindowingMode=`（返回窗口模式），而不是 `<unknown>`。
 */
class Issue238RedFirstTest {

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

    /**
     * 没有 ActivityRecord compat 字段行的 freeform 任务块（合成但字段形态真实）：块内唯一
     * 的 `mWindowingMode=` 在 winConfig 里，值为 `freeform`。这种块必须给出 `<unknown>`。
     */
    private val freeformTaskWithoutCompat = """
  * Task{b22 #2047 type=standard A=10235:com.xingin.xhs U=0 visible=true visibleRequested=true mode=freeform translucent=false sz=1}
    mLastNonFullscreenBounds=Rect(269, 536 - 811, 1616)
    isSleeping=false
    topResumedActivity=ActivityRecord{751a1f6 u0 com.xingin.xhs/.index.v2.IndexActivityV2 t2047}
    * Hist  #0: ActivityRecord{751a1f6 u0 com.xingin.xhs/.index.v2.IndexActivityV2 t2047}
      packageName=com.xingin.xhs processName=com.xingin.xhs
      rootOfTask=true task=Task{b22 #2047 type=standard A=10235:com.xingin.xhs}
      stateNotNeeded=false componentSpecified=true mActivityType=standard
      mLastReportedConfigurations:
        mGlobalConfig={1.0 460mcc2mnc [zh_CN] ldltr sw432dp w432dp h861dp 400dpi nrml long port -touch -keyb/v/h -nav/h winConfig={ mBounds=Rect(0, 0 - 853, 1169) mAppBounds=Rect(0, 0 - 853, 1169) mMaxBounds=Rect(0, 0 - 1080, 2152) mDisplayRotation=ROTATION_0 mWindowingMode=freeform mActivityType=undefined mAlwaysOnTop=undefined mRotation=ROTATION_0} as.5 s.3334 fontWeightAdjustment=163}
      CurrentConfiguration={1.0 460mcc2mnc [zh_CN] ldltr sw432dp w432dp h861dp 400dpi nrml long port -touch -keyb/v/h -nav/h winConfig={ mBounds=Rect(0, 0 - 853, 1169) mAppBounds=Rect(0, 0 - 853, 1169) mMaxBounds=Rect(0, 0 - 1080, 2152) mDisplayRotation=ROTATION_0 mWindowingMode=freeform mActivityType=standard mAlwaysOnTop=undefined mRotation=ROTATION_0} as.5 s.60 fontWeightAdjustment=163}
"""

    @Test
    fun `compat must read the activity record compat flag instead of the winConfig windowing mode`() {
        // issue #238：真 compat 是 ActivityRecord 的 `compat={560dpi}`；base 上首选
        // `mWindowingMode=` 恒返回 "fullscreen"，这个维度从上线起就没被读到过。
        assertEquals(
            "必须读到 ActivityRecord 的 screen-compat 标志（带花括号，与 dump 原形态一致）",
            "{560dpi}",
            ActivityDumpParser.extractCompat(task17OnVd)
        )
    }

    @Test
    fun `compat must stay unknown when the block has no activity record compat flag`() {
        // issue #238：块内只有 winConfig 的 `mWindowingMode=freeform`，没有 compat 行——
        // 不得回落到窗口模式（base 上会返回 "freeform"），取不到应为 null（日志记 <unknown>）。
        assertNull(
            "没有 compat 字段行时必须返回 null，禁止回落到 mWindowingMode",
            ActivityDumpParser.extractCompat(freeformTaskWithoutCompat)
        )
    }

    @Test
    fun `density must not be taken from the compat flag`() {
        // issue #238：块内首个 `NNNdpi` 是 compat={560dpi}；density 只认 densityDpi= /
        // CurrentConfiguration 段（且排除 winConfig），必须读到任务真实配置 400。
        val density = ActivityDumpParser.extractDensity(task17OnVd)
        assertEquals("必须读到任务 Configuration 的真实密度 400", "400", density)
        assertEquals("不得被 compat={560dpi} 误命中", false, density == "560")
    }
}

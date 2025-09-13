// === Imports ===
import static qupath.lib.gui.scripting.QPEx.*
import qupath.ext.warpy.Warpy
import qupath.lib.images.servers.ServerTools
import qupath.lib.regions.ImagePlane
import net.imglib2.realtransform.RealTransform

import java.nio.charset.StandardCharsets
import java.util.Locale

// ====== 点数列候选名（按实际需要修改）======
def EXTRA_COL_SOURCES = [
    "Esr1 (Opal 480): Num Spots": [
        "Esr1 (Opal 480): Num Spots",
        "Esr1: Num Spots",
        "Subcellular: Channel 2: Num single spots",
        "Channel 2: Num single spots"
    ],
    "Prg (Opal 520): Num Spots": [
        "Prg (Opal 520): Num Spots",
        "Subcellular: Channel 3: Num single spots",
        "Channel 3: Num single spots"
    ],
    "Prlr (Opal 570): Num Spots": [
        "Prlr (Opal 570): Num Spots",
        "Subcellular: Channel 4: Num single spots",
        "Channel 4: Num single spots"
    ],
    "Oxt (Opal 620): Num Spots": [
        "Oxt (Opal 620): Num Spots",
        "Subcellular: Channel 5: Num single spots",
        "Channel 5: Num single spots"
    ]
]

// ====== 参数 ======
boolean EXPORT_CELLS_ONLY = false
boolean DROP_BY_RANGE = true
double  MAX_ABS_MM = 30.0
int     MAX_PER_IMAGE = Integer.MAX_VALUE

// ====== 日志工具 ======
def info(msg){ println "INFO: [ABBA-Batch] " + msg }
def warn(msg){ println "WARN: [ABBA-Batch] " + msg }
def err (msg){ println "ERR : [ABBA-Batch] " + msg }

// ====== 工具函数 ======
def readValueByName = { ml, String name ->
    if (ml == null) return null
    Double v = null
    try { v = ml.getValue(name) as Double } catch (ignored) {}
    if (v == null) { try { v = ml.getMeasurementValue(name) as Double } catch (ignored) {} }
    return v
}

def centroidSrc = { obj, int srcDims ->
    def roi = obj.getROI(); if (roi == null) return null
    double x = roi.getCentroidX(), y = roi.getCentroidY(), zIdx = 0
    try {
        def plane = roi.getImagePlane()
        if (plane != null && plane != ImagePlane.getDefaultPlane())
            zIdx = plane.getZ()
    } catch (ignored) {}
    if (srcDims == 2) return new double[]{x, y}
    if (srcDims >= 3) return new double[]{x, y, zIdx as double}
    return new double[]{x, y}
}

def probeTransform = { RealTransform T, List objs ->
    int sd = T.numSourceDimensions(), td = T.numTargetDimensions()
    int n = Math.min(200, objs.size())
    List<Double> xs = []; List<Double> ys = []; int ok=0
    for (int i=0; i<n; i++) {
        def src = centroidSrc(objs[i], sd); if (src == null) continue
        double[] tgt = new double[td]
        try {
            T.apply(src, tgt)
            if (!Double.isNaN(tgt[0]) && !Double.isNaN(tgt[1])) { xs << tgt[0]; ys << tgt[1]; ok++ }
        } catch (ignored) {}
    }
    def inRange = xs.count{ it>=-1 && it<=20 } + ys.count{ it>=-1 && it<=20 }
    def p90Abs = [xs,ys].collect{ it.collect{ Math.abs(it) }.sort().with{ size()>0 ? get((int)(0.9*(size()-1))) : Double.NaN } }
    [ok:ok, inRange:inRange, p90x:p90Abs[0], p90y:p90Abs[1], sd:sd, td:td]
}

// ====== 主流程 ======
def project = getProject()
if (project == null) { err("没有打开项目！"); return }

// 导出目录 = 与 .qpproj 同级的 exports
def projectFile = project.getPath().toFile()
def projectBaseDir = projectFile.getParentFile()
if (projectBaseDir == null || !projectBaseDir.exists()) { err("无法定位项目目录！"); return }
File outRoot = new File(projectBaseDir, "exports")
if (!outRoot.exists()) outRoot.mkdirs()
info("所有 TSV 将写入: " + outRoot.getAbsolutePath())

int totalOK=0, totalSkip=0, totalImages=0

project.getImageList().each { entry ->
    def entryName = entry.getImageName()
    info("—— 处理条目：" + entryName)

    // transform.json（在条目目录里）
    List<File> tfCandidates = []
    def entryDir = entry.getEntryPath()?.toFile()
    if (entryDir?.exists()) {
        tfCandidates.addAll(entryDir.listFiles({d,n-> n.toLowerCase().startsWith("abba-transform") && n.toLowerCase().endsWith(".json")} as FilenameFilter) ?: [])
        if (tfCandidates.isEmpty()) {
            def fb = new File(entryDir, "ABBA-Transform.json")
            if (fb.exists()) tfCandidates.add(fb)
        }
    }
    if (tfCandidates.isEmpty()) { warn("未找到 transform JSON，跳过。"); return }
    tfCandidates.sort { -it.lastModified() }
    def transformFile = tfCandidates[0]

    // 对象
    def imageData = entry.readImageData()
    def hier = imageData.getHierarchy()
    List objs = EXPORT_CELLS_ONLY ? (hier.getCellObjects() as List) :
                 (!hier.getCellObjects().isEmpty() ? (hier.getCellObjects() as List) : (hier.getDetectionObjects() as List))
    if (objs.isEmpty()) { warn("无对象，跳过。"); imageData.close(); return }
    if (objs.size() > MAX_PER_IMAGE) objs = objs.subList(0, MAX_PER_IMAGE)

    // 变换：探测 + 只按被选用的变换判断是否 µm→mm
    RealTransform T_fwd = Warpy.getRealTransform(transformFile)
    RealTransform T_inv = T_fwd.inverse()
    def pf = probeTransform(T_fwd, objs)
    def pi = probeTransform(T_inv, objs)

    RealTransform T
    boolean usingForward
    if (pf.inRange >= pi.inRange) { T = T_fwd; usingForward = true }
    else                          { T = T_inv; usingForward = false }

    double p90x = usingForward ? (pf.p90x ?: Double.NaN) : (pi.p90x ?: Double.NaN)
    double p90y = usingForward ? (pf.p90y ?: Double.NaN) : (pi.p90y ?: Double.NaN)
    double scale = (Double.isFinite(p90x) && Double.isFinite(p90y) && Math.max(p90x, p90y) > 100.0) ? 1.0/1000.0 : 1.0

    info(String.format(Locale.US,
        "选择使用 %s 变换；p90X=%.2f, p90Y=%.2f ⇒ scale=%s",
        usingForward ? "正向" : "逆向", p90x, p90y, (scale==1.0 ? "1 (mm)" : "1/1000 (µm→mm)")))

    // 写 TSV
    def cleanName = entryName.replaceAll(/\..+$/, "")
    def outPath = new File(outRoot, cleanName + ".tsv")
    outPath.getParentFile().mkdirs()

    def writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(outPath), StandardCharsets.UTF_8))
    def header = ["Class", "Allen CCFv3 X mm", "Allen CCFv3 Y mm", "Allen CCFv3 Z mm"] + EXTRA_COL_SOURCES.keySet().toList()
    writer.write(header.join("\t")); writer.newLine()

    int nOK=0, nSkip=0; Locale us = Locale.US
    int sd = T.numSourceDimensions(), td = T.numTargetDimensions()

    objs.each { obj ->
        def src = centroidSrc(obj, sd); if (src == null) { nSkip++; return }
        double[] tgt = new double[td]
        try { T.apply(src, tgt) } catch (e) { nSkip++; return }

        double X = (td>0 ? tgt[0] : Double.NaN) * scale
        double Y = (td>1 ? tgt[1] : Double.NaN) * scale
        double Z = (td>2 ? tgt[2] : 0.0       ) * scale
        if (!(Double.isFinite(X) && Double.isFinite(Y) && Double.isFinite(Z))) { nSkip++; return }
        if (DROP_BY_RANGE && (Math.abs(X)>MAX_ABS_MM||Math.abs(Y)>MAX_ABS_MM||Math.abs(Z)>MAX_ABS_MM)) { nSkip++; return }

        String cls = obj.getPathClass() != null ? obj.getPathClass().toString() : "Positive"
        List<String> row = [cls, String.format(us,"%.4f",X), String.format(us,"%.4f",Y), String.format(us,"%.4f",Z)]

        def ml = obj.getMeasurementList()
        EXTRA_COL_SOURCES.each { outName, candNames ->
            Double val = null
            if (ml != null) {
                for (String cand : candNames) {
                    val = readValueByName(ml, cand)
                    if (val != null && Double.isFinite(val)) break else val=null
                }
            }
            int iv = (val != null) ? Math.round(val as float) : 0
            row << Integer.toString(iv)
        }
        writer.write(row.join("\t")); writer.newLine()
        nOK++
    }
    writer.close()

    info("完成 ${entryName}: 成功 ${nOK}, 跳过 ${nSkip}, 输出 -> " + outPath.getAbsolutePath())
    totalOK+=nOK; totalSkip+=nSkip; totalImages++
    imageData.close()
}

info("==== 批处理结束 ====")
info("共 ${totalImages} 张图像；总成功 ${totalOK} 行；总跳过 ${totalSkip} 行。")

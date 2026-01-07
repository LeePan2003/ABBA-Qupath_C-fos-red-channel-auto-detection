import qupath.lib.gui.scripting.QPEx
import qupath.lib.objects.PathAnnotationObject
import qupath.lib.objects.PathDetectionObject
import java.util.Locale

// ===== 配置 =====
final String CHANNEL_NAME = 'Red'
final String OUT_DIR = 'results'
final String OUT_NAME = 'cfos_counts_red_all_images.csv'

// ===== 输出准备 =====
def outDir = buildFilePath(PROJECT_BASE_DIR, OUT_DIR)
mkdirs(outDir)
def outFile = new File(buildFilePath(PROJECT_BASE_DIR, OUT_DIR, OUT_NAME))
if (!outFile.exists())
    outFile.text = "Image,AnnotationID,AnnotationName,Channel,Cells,Area_corrected,Density_per_mm2\n"

// ------- 读 annotation 面板里“原始显示值”（读不到就用 ROI 像素面积） -------
double readShownArea(def ann) {
    def ml = ann.getMeasurementList()
    def candidates = [
        'Area µm^2','Area μm^2','Area (µm^2)','Area (μm^2)',
        'Area um^2','Area (um^2)','Area px^2','ROI area','Area'
    ]
    for (name in candidates) {
        try {
            double v = ml.getMeasurementValue(name)
            if (v > 0 && Double.isFinite(v))
                return v
        } catch (Throwable ignore) {}
    }
    def roi = ann.getROI()
    return (roi != null) ? roi.getArea() : Double.NaN
}

// ===== 遍历项目内所有图像 =====
for (entry in getProject().getImageList()) {
    try {
        def imageData = entry.readImageData()
        if (imageData == null) {
            print "WARN: 无法读取图像数据，跳过：${entry.getImageName()}"
            continue
        }

        def server = imageData.getServer()
        def md = server.getMetadata()
        def channelNames = md.getChannels()*.getName()
        def hasRed = channelNames.any { it?.equalsIgnoreCase(CHANNEL_NAME) }
        if (!hasRed) {
            print "WARN: ${entry.getImageName()} 未找到通道 '${CHANNEL_NAME}'；可用通道：${channelNames}"
            continue
        }

        // 每张片子读取自身的像素标定
        def cal = server.getPixelCalibration()
        double pw = (cal != null) ? cal.getPixelWidthMicrons()  : md.getPixelWidthMicrons()
        double ph = (cal != null) ? cal.getPixelHeightMicrons() : md.getPixelHeightMicrons()
        if (!(pw > 0) || !(ph > 0)) {
            print "WARN: ${entry.getImageName()} 像素标定无效（pw=${pw}, ph=${ph}），本图面积/密度将为 NaN"
        }
        double factorSq = Math.pow(pw * ph, 2)  // 你要求的 (pw×ph)^2

        def hierarchy = imageData.getHierarchy()
        def leafAnns = hierarchy.getAnnotationObjects().findAll { ann ->
            !ann.getChildObjects().any { it instanceof PathAnnotationObject }
        }

        for (ann in leafAnns) {
            def roi = ann.getROI()
            if (roi == null) continue

            // 清理旧检测
            def oldDets = hierarchy.getObjectsForROI(PathDetectionObject, roi)
            if (oldDets && !oldDets.isEmpty())
                hierarchy.removeObjects(oldDets, true)

            // 选择 annotation（不触碰 GUI）
            def sel = hierarchy.getSelectionModel()
            sel.clearSelection()
            sel.setSelectedObject(ann, true)

            // 细胞检测（显式传 imageData）
            def args = """
            {
              "detectionImage":"${CHANNEL_NAME}",
              "requestedPixelSizeMicrons":0.5,
              "backgroundRadiusMicrons":8.0,
              "medianRadiusMicrons":0.0,
              "sigmaMicrons":2.5,
              "minAreaMicrons":10.0,
              "maxAreaMicrons":400.0,
              "threshold":10.0,
              "watershedPostProcess":true,
              "cellExpansionMicrons":0.0,
              "includeNuclei":true,
              "smoothBoundaries":true,
              "makeMeasurements":true
            }
            """.stripIndent()
            QPEx.runPlugin('qupath.imagej.detect.cells.WatershedCellDetection', imageData, args)

            // ROI 内细胞数
            def detsInROI = hierarchy.getObjectsForROI(PathDetectionObject, roi)
            int nCells = detsInROI == null ? 0 : detsInROI.size()

            // === 关键：按你的要求修正 ===
            // 修正面积 =（annotation面板里“原始输出值”）× (pw×ph)^2
            double shown = readShownArea(ann)
            double areaCorrected = (pw > 0 && ph > 0 && shown > 0) ? (shown * factorSq) : Double.NaN
            double densityPerMM2 = (areaCorrected > 0) ? (nCells / (areaCorrected / 1.0e6)) : Double.NaN

            // 写 CSV
            def annName = (ann.getName() ?: ann.getPathClass()?.getName() ?: "").replaceAll(/[\r\n,]/, " ")
            outFile << "${entry.getImageName()},${ann.getID()},${annName},${CHANNEL_NAME},${nCells}," +
                      String.format(Locale.ROOT, "%.6f", areaCorrected) + "," +
                      String.format(Locale.ROOT, "%.6f", densityPerMM2) + "\n"
        }

        entry.saveImageData(imageData)

    } catch (Exception e) {
        print "ERROR: 处理图像 ${entry.getImageName()} 失败：${e.getMessage()}"
    }
}

// 同步整个项目
getProject().syncChanges()

print "✅ 面积按你的公式：Area_corrected = (annotation原值) × (pixelWidth×pixelHeight)^2；每张片子分别读取其像素标定后再计算。"

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
    outFile.text = "Image,AnnotationID,AnnotationName,Channel,Cells,Area_um2,Density_per_mm2\n"

// ===== 遍历项目内所有图像（无任何 GUI 调用）=====
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

        def hierarchy = imageData.getHierarchy()
        def allAnns = hierarchy.getAnnotationObjects()
        // 仅叶子 annotation（无子 annotation）
        def leafAnns = allAnns.findAll { ann ->
            !ann.getChildObjects().any { it instanceof PathAnnotationObject }
        }

        // 遍历叶子 annotation
        for (ann in leafAnns) {
            def roi = ann.getROI()
            if (roi == null) continue

            // 清理该 ROI 内旧的检测，避免叠加
            def oldDets = hierarchy.getObjectsForROI(PathDetectionObject, roi)
            if (oldDets && !oldDets.isEmpty())
                hierarchy.removeObjects(oldDets, true)

            // 通过层级选择模型选择 annotation（不触碰 GUI）
            def sel = hierarchy.getSelectionModel()
            sel.clearSelection()
            sel.setSelectedObject(ann, true)

            // 运行细胞检测 —— 显式把 imageData 传给插件（关键：不依赖 Viewer）
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

            // 用空间查询统计 ROI 内的细胞数（最稳妥，不依赖父子层级）
            def detsInROI = hierarchy.getObjectsForROI(PathDetectionObject, roi)
            int nCells = detsInROI == null ? 0 : detsInROI.size()

            // 面积与密度
            double pw = md.getPixelWidthMicrons()
            double ph = md.getPixelHeightMicrons()
            double area_um2 = roi.getScaledArea(pw, ph)  // 像素尺寸标定后面积（μm²）
            double density_per_mm2 = (area_um2 > 0) ? (nCells / (area_um2 / 1.0e6)) : Double.NaN

            // 写 CSV（逐行追加，避免丢行）
            def annName = (ann.getName() ?: ann.getPathClass()?.getName() ?: "").replaceAll(/[\r\n,]/, " ")
            outFile << "${entry.getImageName()},${ann.getID()},${annName},${CHANNEL_NAME},${nCells}," +
                      String.format(Locale.ROOT, "%.3f", area_um2) + "," +
                      String.format(Locale.ROOT, "%.3f", density_per_mm2) + "\n"
        }

        // 保存该图像的更改
        entry.saveImageData(imageData)

    } catch (Exception e) {
        print "ERROR: 处理图像 ${entry.getImageName()} 失败：${e.getMessage()}"
        // 出错也继续下一张
    }
}

// 同步整个项目
getProject().syncChanges()

print "✅ 完成：已对项目全部图像在“Red”通道执行细胞检测并汇总到 results/${OUT_NAME}"

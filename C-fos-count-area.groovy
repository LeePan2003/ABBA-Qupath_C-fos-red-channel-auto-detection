// QuPath 0.6.0 script -- 批量统计整个 Project 中 annotation 的红通道 (C-fos) 和真实面积
// 面积经过像素矫正：(pixelWidth × pixelHeight)^2
// CSV 输出到第一个图像实际所在文件夹

import qupath.lib.projects.Project
import qupath.lib.projects.ProjectImageEntry
import qupath.lib.objects.PathAnnotationObject
import qupath.lib.objects.PathDetectionObject
import qupath.lib.roi.interfaces.ROI
import java.nio.file.Files

// 获取当前 Project
def project = getProject()
if (project == null) {
    print('没有打开 Project，脚本退出')
    return
}

// 获取第一个图像实际路径，去掉前缀
def firstEntry = project.getImageList().get(0)
def serverPath = firstEntry.readImageData().getServer().getPath() // 带前缀
def pathStr = serverPath.replaceAll(".*file:", "")                // 去掉 "PyramidGeneratingImageServer:BioFormatsImageServer:" 前缀
def projectDir = new File(pathStr).getParentFile()

// CSV 输出路径
def outFile = new File(projectDir, "qupath_project_annotation_counts.csv")

// CSV header
def sb = new StringBuilder()
sb << "Image,AnnotationName,Area_um2,NumDetections,Density_per_mm2\n"

// 遍历 Project 所有图像
project.getImageList().each { ProjectImageEntry entry ->

    // 打开图像
    def imageData = entry.readImageData()
    def hierarchy = imageData.getHierarchy()
    def detections = hierarchy.getDetectionObjects()
    def imageName = entry.getImageName()

    // 获取像素校准信息
    def cal = imageData.getServer().getPixelCalibration()
    double pxW = cal.getPixelWidthMicrons()   // 像素宽 µm
    double pxH = cal.getPixelHeightMicrons()  // 像素高 µm

    // 获取所有 annotation
    def annotations = hierarchy.getAnnotationObjects()
    if (annotations.isEmpty()) return

    annotations.each { annObj ->
        if (!annObj.isAnnotation()) return
        def name = annObj.getName() ?: ""

        // ----------------------------
        // 1. 计算 Annotation 面积（µm²）
        ROI roi = annObj.getROI()
        double area_px = roi.getArea()  
        double area_um2 = area_px * Math.pow(pxW * pxH, 2) // ✅ 正确面积矫正

        // ----------------------------
        // 2. 获取 Num Detections（红色通道 C-fos 数量）
        def ml = annObj.getMeasurementList()
        Double numDet = null
        if (ml.getMeasurementNames().contains("Num Detections")) {
            numDet = ml.getMeasurementValue("Num Detections")
        }

        // 如果没有 Num Detections，则按 detection 质心落入 annotation 内统计
        if (numDet == null) {
            long count = 0
            double x0 = roi.getBoundsX()
            double y0 = roi.getBoundsY()
            double w  = roi.getBoundsWidth()
            double h  = roi.getBoundsHeight()

            detections.each { d ->
                try {
                    def droi = d.getROI()
                    double cx = droi.getCentroidX()
                    double cy = droi.getCentroidY()
                    if (cx < x0 || cx > x0+w || cy < y0 || cy > y0+h) return
                    if (roi.contains(cx, cy)) count++
                } catch (Exception e) {}
            }
            numDet = (double) count
        }

        // ----------------------------
        // 3. 计算密度 (cells/mm²)
        double density_per_mm2 = (area_um2 > 0) ? numDet / (area_um2 / 1e6) : 0.0

        // ----------------------------
        // 4. 写入 CSV
        sb << "\"${imageName}\","
        sb << "\"${name.replace('"','""')}\","
        sb << String.format(Locale.US, "%.3f,", area_um2)
        sb << String.format(Locale.US, "%.0f,", numDet)
        sb << String.format(Locale.US, "%.3f\n", density_per_mm2)
    }

    // 释放 imageData 避免内存占用过大
    imageData = null
}

// 写入 CSV
try {
    Files.write(outFile.toPath(), sb.toString().getBytes("UTF-8"))
    print("已导出 CSV -> ${outFile.toString()}")
} catch (Exception e) {
    print("导出 CSV 失败： " + e.toString())
}

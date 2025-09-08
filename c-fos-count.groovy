// QuPath 0.6.0 script -- 批量统计整个 Project 中 annotation 的红通道 (C-fos) 和面积
// 输出总 CSV 文件，完全兼容 QuPath 0.6.0

import qupath.lib.projects.Project
import qupath.lib.projects.ProjectImageEntry
import qupath.lib.objects.PathAnnotationObject
import qupath.lib.objects.PathDetectionObject
import qupath.lib.roi.interfaces.ROI
import javax.swing.JFileChooser
import java.nio.file.Files

// 选择保存 CSV 文件
def chooser = new JFileChooser()
chooser.setDialogTitle('Save CSV - export project annotation counts')
chooser.setSelectedFile(new File(System.getProperty('user.home'), 'qupath_project_annotation_counts.csv'))
def result = chooser.showSaveDialog(null)
if (result != JFileChooser.APPROVE_OPTION) {
    print('未选择保存路径，脚本取消导出')
    return
}
def outFile = chooser.getSelectedFile()

// CSV header
def sb = new StringBuilder()
sb << "Image,AnnotationName,Area_um2,NumDetections,Density_per_mm2\n"

// 获取当前 Project
def project = getProject()
if (project == null) {
    print('没有打开 Project，脚本退出')
    return
}

// 遍历 Project 所有图像
project.getImageList().each { ProjectImageEntry entry ->

    // 打开图像
    def imageData = entry.readImageData()
    def hierarchy = imageData.getHierarchy()
    def detections = hierarchy.getDetectionObjects()
    def imageName = entry.getImageName()

    // 获取所有 annotation
    def annotations = hierarchy.getAnnotationObjects()
    if (annotations.isEmpty()) return

    annotations.each { annObj ->
        if (!annObj.isAnnotation()) return
        def name = annObj.getName() ?: ""

        // 获取 measurement list
        def ml = annObj.getMeasurementList()

        // 面积 (µm²)
        double area_um2 = 0.0
        if (ml.getMeasurementNames().contains("Area µm^2")) {
            area_um2 = ml.getMeasurementValue("Area µm^2")
        }

        // Num Detections
        Double numDet = null
        if (ml.getMeasurementNames().contains("Num Detections")) {
            numDet = ml.getMeasurementValue("Num Detections")
        }

        // 如果没有 Num Detections，则按 detection 质心落入 annotation 内统计
        if (numDet == null) {
            long count = 0
            ROI roi = annObj.getROI()
            // 使用 getBoundsX/Y/Width/Height 代替 getBounds()
            double x0 = roi.getBoundsX()
            double y0 = roi.getBoundsY()
            double w  = roi.getBoundsWidth()
            double h  = roi.getBoundsHeight()

            detections.each { d ->
                try {
                    def droi = d.getROI()
                    double cx = droi.getCentroidX()
                    double cy = droi.getCentroidY()
                    // 快速矩形筛选
                    if (cx < x0 || cx > x0+w || cy < y0 || cy > y0+h) return
                    // 精确判断质心是否在 annotation 内
                    if (roi.contains(cx, cy)) count++
                } catch (Exception e) {}
            }
            numDet = (double) count
        }

        // 密度 cells / mm²
        double density_per_mm2 = (area_um2 > 0) ? numDet / (area_um2 / 1e6) : 0.0

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

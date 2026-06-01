import CoreGraphics
import Foundation
import ImageIO
import UniformTypeIdentifiers

let iconFiles: [(name: String, pixels: Int)] = [
    ("Icon-20@2x.png", 40),
    ("Icon-20@3x.png", 60),
    ("Icon-29@2x.png", 58),
    ("Icon-29@3x.png", 87),
    ("Icon-40@2x.png", 80),
    ("Icon-40@3x.png", 120),
    ("Icon-60@2x.png", 120),
    ("Icon-60@3x.png", 180),
    ("Icon-1024.png", 1024)
]

let iconDirectories = [
    "appclip/BlueChat/Assets.xcassets/AppIcon.appiconset",
    "appclip/BlueChatClip/Assets.xcassets/AppIcon.appiconset"
]

func drawIcon(size: Int) throws -> CGImage {
    let colorSpace = CGColorSpaceCreateDeviceRGB()
    guard let context = CGContext(
        data: nil,
        width: size,
        height: size,
        bitsPerComponent: 8,
        bytesPerRow: size * 4,
        space: colorSpace,
        bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue
    ) else {
        throw NSError(domain: "BlueChatIcon", code: 1, userInfo: [NSLocalizedDescriptionKey: "Could not create CGContext"])
    }

    context.interpolationQuality = .high
    context.scaleBy(x: CGFloat(size) / 1024.0, y: CGFloat(size) / 1024.0)

    let backgroundColors = [
        CGColor(red: 0.02, green: 0.09, blue: 0.19, alpha: 1.0),
        CGColor(red: 0.00, green: 0.46, blue: 0.78, alpha: 1.0),
        CGColor(red: 0.00, green: 0.72, blue: 0.61, alpha: 1.0)
    ] as CFArray
    guard let gradient = CGGradient(colorsSpace: colorSpace, colors: backgroundColors, locations: [0.0, 0.55, 1.0]) else {
        throw NSError(domain: "BlueChatIcon", code: 2, userInfo: [NSLocalizedDescriptionKey: "Could not create gradient"])
    }
    context.drawLinearGradient(gradient, start: CGPoint(x: 0, y: 1024), end: CGPoint(x: 1024, y: 0), options: [])

    context.setFillColor(CGColor(red: 0.06, green: 0.20, blue: 0.42, alpha: 1.0))
    context.fillEllipse(in: CGRect(x: -180, y: 690, width: 520, height: 520))
    context.setFillColor(CGColor(red: 0.03, green: 0.62, blue: 0.83, alpha: 1.0))
    context.fillEllipse(in: CGRect(x: 690, y: -140, width: 480, height: 480))

    let bubble = CGPath(
        roundedRect: CGRect(x: 188, y: 278, width: 648, height: 432),
        cornerWidth: 112,
        cornerHeight: 112,
        transform: nil
    )
    context.setFillColor(CGColor(red: 0.96, green: 0.99, blue: 1.0, alpha: 1.0))
    context.addPath(bubble)
    context.fillPath()

    let tail = CGMutablePath()
    tail.move(to: CGPoint(x: 332, y: 292))
    tail.addLine(to: CGPoint(x: 238, y: 196))
    tail.addLine(to: CGPoint(x: 446, y: 276))
    tail.closeSubpath()
    context.addPath(tail)
    context.fillPath()

    context.setFillColor(CGColor(red: 0.01, green: 0.32, blue: 0.64, alpha: 1.0))
    for x in [338.0, 512.0, 686.0] {
        context.fillEllipse(in: CGRect(x: x - 48.0, y: 458.0, width: 96.0, height: 96.0))
    }

    context.setStrokeColor(CGColor(red: 0.00, green: 0.68, blue: 0.62, alpha: 1.0))
    context.setLineWidth(34)
    context.setLineCap(.round)
    context.move(to: CGPoint(x: 318, y: 690))
    context.addLine(to: CGPoint(x: 224, y: 784))
    context.move(to: CGPoint(x: 706, y: 334))
    context.addLine(to: CGPoint(x: 818, y: 222))
    context.strokePath()

    guard let image = context.makeImage() else {
        throw NSError(domain: "BlueChatIcon", code: 3, userInfo: [NSLocalizedDescriptionKey: "Could not render icon"])
    }
    return image
}

func writePNG(_ image: CGImage, to url: URL) throws {
    guard let destination = CGImageDestinationCreateWithURL(url as CFURL, UTType.png.identifier as CFString, 1, nil) else {
        throw NSError(domain: "BlueChatIcon", code: 4, userInfo: [NSLocalizedDescriptionKey: "Could not create PNG destination"])
    }
    CGImageDestinationAddImage(destination, image, nil)
    guard CGImageDestinationFinalize(destination) else {
        throw NSError(domain: "BlueChatIcon", code: 5, userInfo: [NSLocalizedDescriptionKey: "Could not write \(url.path)"])
    }
}

let root = URL(fileURLWithPath: FileManager.default.currentDirectoryPath)
for directory in iconDirectories {
    let directoryURL = root.appendingPathComponent(directory, isDirectory: true)
    try FileManager.default.createDirectory(at: directoryURL, withIntermediateDirectories: true)
    for iconFile in iconFiles {
        let image = try drawIcon(size: iconFile.pixels)
        try writePNG(image, to: directoryURL.appendingPathComponent(iconFile.name))
    }
}

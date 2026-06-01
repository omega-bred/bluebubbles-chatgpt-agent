import AppKit

let outputPath = CommandLine.arguments.dropFirst().first ?? "appclip/AppStoreConnect/subscription-review.png"
let size = NSSize(width: 1290, height: 2796)
let image = NSImage(size: size)

image.lockFocus()

NSColor(red: 0.07, green: 0.08, blue: 0.10, alpha: 1).setFill()
NSBezierPath(rect: NSRect(origin: .zero, size: size)).fill()

let accent = NSColor(red: 0.20, green: 0.57, blue: 0.96, alpha: 1)
let cardColor = NSColor(red: 0.12, green: 0.14, blue: 0.18, alpha: 1)

let titleAttrs: [NSAttributedString.Key: Any] = [
    .font: NSFont.systemFont(ofSize: 86, weight: .bold),
    .foregroundColor: NSColor.white
]
let subtitleAttrs: [NSAttributedString.Key: Any] = [
    .font: NSFont.systemFont(ofSize: 42, weight: .regular),
    .foregroundColor: NSColor(white: 0.78, alpha: 1)
]
let bodyAttrs: [NSAttributedString.Key: Any] = [
    .font: NSFont.systemFont(ofSize: 44, weight: .medium),
    .foregroundColor: NSColor.white
]
let smallAttrs: [NSAttributedString.Key: Any] = [
    .font: NSFont.systemFont(ofSize: 34, weight: .regular),
    .foregroundColor: NSColor(white: 0.72, alpha: 1)
]

func draw(_ text: String, x: CGFloat, y: CGFloat, width: CGFloat, attrs: [NSAttributedString.Key: Any]) {
    NSString(string: text).draw(
        with: NSRect(x: x, y: y, width: width, height: 220),
        options: [.usesLineFragmentOrigin, .usesFontLeading],
        attributes: attrs
    )
}

draw("BlueChatAI", x: 96, y: 2380, width: 900, attrs: titleAttrs)
draw("Premium", x: 96, y: 2298, width: 900, attrs: subtitleAttrs)

let card = NSBezierPath(roundedRect: NSRect(x: 72, y: 720, width: 1146, height: 1420), xRadius: 34, yRadius: 34)
cardColor.setFill()
card.fill()

draw("Upgrade to Premium", x: 132, y: 1950, width: 1000, attrs: titleAttrs)
draw("Premium model access and 5,000 messages per month.", x: 132, y: 1815, width: 980, attrs: subtitleAttrs)

let rows = [
    "Access the premium assistant model",
    "Higher monthly message allowance",
    "Manage subscription through Apple",
    "Cancel anytime in Apple subscriptions"
]

for (index, row) in rows.enumerated() {
    let y = CGFloat(1610 - index * 150)
    accent.setFill()
    NSBezierPath(ovalIn: NSRect(x: 142, y: y + 8, width: 42, height: 42)).fill()
    draw(row, x: 220, y: y, width: 880, attrs: bodyAttrs)
}

let button = NSBezierPath(roundedRect: NSRect(x: 132, y: 950, width: 1026, height: 118), xRadius: 28, yRadius: 28)
accent.setFill()
button.fill()
let buttonAttrs: [NSAttributedString.Key: Any] = [
    .font: NSFont.systemFont(ofSize: 44, weight: .semibold),
    .foregroundColor: NSColor.white
]
let buttonText = NSString(string: "Subscribe for $5.00/month")
let textSize = buttonText.size(withAttributes: buttonAttrs)
buttonText.draw(at: NSPoint(x: 132 + (1026 - textSize.width) / 2, y: 985), withAttributes: buttonAttrs)

draw("Purchases are processed with StoreKit and validated by BlueChatAI.", x: 132, y: 810, width: 980, attrs: smallAttrs)

image.unlockFocus()

guard
    let tiff = image.tiffRepresentation,
    let bitmap = NSBitmapImageRep(data: tiff),
    let png = bitmap.representation(using: .png, properties: [:])
else {
    fatalError("Unable to render PNG")
}

try png.write(to: URL(fileURLWithPath: outputPath), options: .atomic)

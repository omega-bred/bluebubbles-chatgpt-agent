import Foundation
import StoreKit

struct StoreKitManager {
    func loadProducts(productIds: [String]) async throws -> [Product] {
        try await Product.products(for: productIds)
    }

    func purchase(product: Product, appAccountToken: UUID) async throws -> VerifiedPurchase {
        let result = try await product.purchase(options: [.appAccountToken(appAccountToken)])
        switch result {
        case .success(let verification):
            return try verifiedPurchase(from: verification)
        case .userCancelled:
            throw StoreKitError.userCancelled
        case .pending:
            throw StoreKitError.pending
        @unknown default:
            throw StoreKitError.unknown
        }
    }

    func currentEntitlements(productIds: [String]) async throws -> [VerifiedPurchase] {
        let requestedIds = Set(productIds)
        var purchases: [VerifiedPurchase] = []
        for await verification in Transaction.currentEntitlements {
            let purchase = try verifiedPurchase(from: verification)
            if requestedIds.isEmpty || requestedIds.contains(purchase.transaction.productID) {
                purchases.append(purchase)
            }
        }
        return purchases
    }

    func verifiedPurchase(from verification: VerificationResult<Transaction>) throws -> VerifiedPurchase {
        try VerifiedPurchase(
            transaction: checkVerified(verification),
            jwsRepresentation: verification.jwsRepresentation
        )
    }

    struct VerifiedPurchase {
        let transaction: Transaction
        let jwsRepresentation: String
    }

    private func checkVerified<T>(_ result: VerificationResult<T>) throws -> T {
        switch result {
        case .verified(let value):
            return value
        case .unverified(_, let error):
            throw error
        }
    }

    enum StoreKitError: LocalizedError {
        case userCancelled
        case pending
        case unknown

        var errorDescription: String? {
            switch self {
            case .userCancelled:
                return "Purchase cancelled."
            case .pending:
                return "Purchase pending."
            case .unknown:
                return "Purchase unavailable."
            }
        }
    }
}

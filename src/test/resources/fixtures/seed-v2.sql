-- Seed rows of the scheme-version-2 install (17 section 5.2). FROZEN together with schema-v2.sql: the
-- "existing install" every migration test starts from; never regenerated from current code.
-- Money columns hold the legacy minor units (x100). All values are fixtures, not real credentials.
--
-- 3 categories (one nested), 6 products (one sold out, actions of every legacy type), 2 discounts, 2 coupons
-- (usedCount = 4 on one), 1 creator code, 2 gifts, 2 payment method rows (one with a plaintext secret),
-- 3 orders (PENDING, COMPLETED, REFUNDED) with 5 items.

INSERT INTO `pano_market_category`
    (`id`, `name`, `description`, `icon`, `color`, `status`, `parentId`, `position`, `imageFileName`, `createdAt`, `updatedAt`)
VALUES
    (1, 'Ranks', '<p>Server ranks</p>', 'fa-crown', '#0d6efd', 'ACTIVE', NULL, 0, NULL, 1700000000000, 1700000000000),
    (2, 'VIP Ranks', NULL, 'fa-star', '#ffc107', 'ACTIVE', 1, 0, NULL, 1700000001000, 1700000001000),
    (3, 'Crates', '<p>Crate keys</p>', 'fa-box', '#198754', 'ACTIVE', NULL, 1, 'crates.png', 1700000002000, 1700000002000);

INSERT INTO `pano_market_product`
    (`id`, `slug`, `name`, `description`, `categoryId`, `price`, `creditPrice`, `stock`, `requiredProducts`, `requireOnlyOne`,
     `requiredPermission`, `status`, `featured`, `durationType`, `durationStart`, `durationExpiry`, `priority`, `icon`,
     `imageFileName`, `actions`, `createdAt`, `updatedAt`)
VALUES
    -- all three legacy action types in one product
    (1, 'vip-rank', 'VIP Rank', '<p>VIP perks; costs 19.99</p>', 2, 1999, 0, NULL, '[]', 0, NULL, 'ACTIVE', 1, 'LIFETIME', NULL, NULL, 10,
     'fa-star', NULL,
     '[{"type":"PERMISSION","value":["vip.use","vip.fly"]},{"type":"COMMAND","value":["lp user {player} parent add vip"],"delay":0,"targetServers":[1]},{"type":"CREDIT","value":100.0}]',
     1700000010000, 1700000010000),
    (2, 'starter-crate', 'Starter Crate', '<p>A crate key</p>', 3, 1000, 0, 100, '[]', 0, NULL, 'ACTIVE', 0, 'LIFETIME', NULL, NULL, 5,
     'fa-box', NULL,
     '[{"type":"COMMAND","value":["crate give {player} starter 1"]}]',
     1700000011000, 1700000011000),
    (3, 'credit-pack-500', 'Credit Pack 500', NULL, NULL, 500, 0, NULL, '[]', 0, NULL, 'ACTIVE', 0, 'LIFETIME', NULL, NULL, 0,
     'fa-coins', NULL,
     '[{"type":"CREDIT","value":500}]',
     1700000012000, 1700000012000),
    -- sold out, requires another product, temporary
    (4, 'legend-rank', 'Legend Rank', '<p>Sold out</p>', 1, 4999, 0, 0, '[1]', 1, 'vip.use', 'ACTIVE', 1, 'TEMPORARY', 1700000000000, 1900000000000, 20,
     'fa-gem', NULL,
     '[{"type":"PERMISSION","value":["legend.use"]}]',
     1700000013000, 1700000013000),
    (5, 'mystery-box', 'Mystery Box', NULL, NULL, 750, 0, 25, '[]', 0, NULL, 'HIDDEN', 0, 'LIFETIME', NULL, NULL, 0,
     'fa-box', NULL, NULL, 1700000014000, 1700000014000),
    (6, 'old-kit', 'Old Kit', '<p>Retired</p>', 3, 500, 300, 10, '[]', 0, NULL, 'INACTIVE', 0, 'LIFETIME', NULL, NULL, 5,
     'fa-box', NULL, '[]', 1700000015000, 1700000015000);

INSERT INTO `pano_market_discount`
    (`id`, `name`, `value`, `unit`, `minPaymentAmount`, `scope`, `productIds`, `categoryIds`, `startDate`, `expiryDate`,
     `usageLimit`, `usedCount`, `status`, `createdAt`, `updatedAt`)
VALUES
    (1, 'Summer sale', 20, 'PERCENT', NULL, 'ALL', NULL, NULL, NULL, NULL, NULL, 7, 'ACTIVE', 1700000020000, 1700000020000),
    (2, 'Rank discount', 500, 'FIXED', 1000, 'CATEGORIES', NULL, '[1,2]', 1700000000000, 1900000000000, 50, 0, 'INACTIVE', 1700000021000, 1700000021000);

INSERT INTO `pano_market_coupon`
    (`id`, `name`, `code`, `scope`, `productIds`, `discount`, `unit`, `minPaymentAmount`, `startDate`, `expiryDate`,
     `redeemLimit`, `customerRedeemLimit`, `usedCount`, `status`, `createdAt`, `updatedAt`)
VALUES
    (1, 'Welcome', 'WELCOME10', 'ALL', NULL, 10, 'PERCENT', NULL, NULL, NULL, 100, 1, 4, 'ACTIVE', 1700000030000, 1700000030000),
    (2, 'Five off', 'SAVE5', 'SELECTED', '[1,2]', 500, 'FIXED', 2000, NULL, NULL, NULL, NULL, 0, 'ACTIVE', 1700000031000, 1700000031000);

INSERT INTO `pano_market_creator_code`
    (`id`, `creator`, `code`, `discount`, `unit`, `commissionPercent`, `startDate`, `expiryDate`, `redeemLimit`, `usedCount`,
     `earnings`, `status`, `createdAt`, `updatedAt`)
VALUES
    (1, 'SteveTV', 'STEVE', 5, 'PERCENT', 10, NULL, NULL, NULL, 2, 380, 'ACTIVE', 1700000040000, 1700000040000);

INSERT INTO `pano_market_gift`
    (`id`, `code`, `type`, `productId`, `creditAmount`, `productIds`, `status`, `startDate`, `expiryDate`, `createdAt`, `updatedAt`)
VALUES
    (1, 'GIFT-PRODUCT-1', 'PRODUCT', 2, NULL, NULL, 'ACTIVE', NULL, NULL, 1700000050000, 1700000050000),
    (2, 'GIFT-CREDIT-1', 'CREDIT', NULL, 1000, NULL, 'ACTIVE', NULL, 1900000000000, 1700000051000, 1700000051000);

INSERT INTO `pano_market_payment_method`
    (`id`, `methodId`, `enabled`, `settings`, `createdAt`, `updatedAt`)
VALUES
    -- legacy row: the secret is stored in plaintext
    (1, 'tebex', 1, '{"webstoreId":"fixture-store","secret":"fixture-plaintext-secret","mode":"live"}', 1700000060000, 1700000060000),
    (2, 'paytr', 0, '{"merchantId":"fixture-merchant"}', 1700000061000, 1700000061000);

-- Orders: totals equal the sum of their items (unitPrice x quantity).
INSERT INTO `pano_market_order`
    (`id`, `userId`, `playerUsername`, `totalPrice`, `currency`, `paymentMethodId`, `paymentLabel`, `status`, `createdAt`, `updatedAt`, `exchangeRate`)
VALUES
    (1, 101, 'Steve', 1999, 'TRY', 'tebex', 'Tebex', 'PENDING', 1700000100000, 1700000100000, NULL),
    (2, 102, 'Alex', 2500, 'USD', 'tebex', 'Tebex', 'COMPLETED', 1700000200000, 1700000250000, 32.5),
    (3, 103, 'Herobrine', 1500, 'TRY', 'paytr', 'PayTR', 'REFUNDED', 1700000300000, 1700000350000, NULL);

INSERT INTO `pano_market_order_item`
    (`id`, `orderId`, `productId`, `productName`, `quantity`, `unitPrice`, `createdAt`, `updatedAt`)
VALUES
    (1, 1, 1, 'VIP Rank', 1, 1999, 1700000100000, 1700000100000),
    (2, 2, 2, 'Starter Crate', 2, 1000, 1700000200000, 1700000200000),
    (3, 2, 3, 'Credit Pack 500', 1, 500, 1700000200000, 1700000200000),
    (4, 3, 5, 'Mystery Box', 1, 750, 1700000300000, 1700000300000),
    (5, 3, NULL, 'Retired Kit', 1, 750, 1700000300000, 1700000300000);

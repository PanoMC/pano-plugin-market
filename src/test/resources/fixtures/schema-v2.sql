-- Scheme version 2 of the market plugin: the DDL of the ten tables as the Dao.init implementations created
-- them at commit c58d57a (copied verbatim, table prefix pano_). FROZEN (01 section 14.1 rule 6): the starting
-- point of every migration test, never regenerated from current code.

CREATE TABLE IF NOT EXISTS `pano_market_category` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` VARCHAR(255) NOT NULL,
  `description` MEDIUMTEXT,
  `icon` VARCHAR(64) NOT NULL DEFAULT 'fa-folder',
  `color` VARCHAR(16) NOT NULL DEFAULT '#0d6efd',
  `status` VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  `parentId` bigint,
  `position` int NOT NULL DEFAULT 0,
  `imageFileName` VARCHAR(255),
  `createdAt` BIGINT(20) NOT NULL,
  `updatedAt` BIGINT(20) NOT NULL,
  PRIMARY KEY (`id`),
  INDEX (`parentId`, `position`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Market category table.';

CREATE TABLE IF NOT EXISTS `pano_market_comparison` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` VARCHAR(255) NOT NULL,
  `status` VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  `priority` INT NOT NULL DEFAULT 0,
  `productIds` MEDIUMTEXT,
  `features` MEDIUMTEXT,
  `cellValues` MEDIUMTEXT,
  `createdAt` BIGINT(20) NOT NULL,
  `updatedAt` BIGINT(20) NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Market comparison table.';

CREATE TABLE IF NOT EXISTS `pano_market_coupon` (
    `id` bigint NOT NULL AUTO_INCREMENT,
    `name` VARCHAR(255) NOT NULL DEFAULT '',
    `code` VARCHAR(64) NOT NULL,
    `scope` VARCHAR(16) NOT NULL DEFAULT 'ALL',
    `productIds` MEDIUMTEXT,
    `discount` BIGINT NOT NULL,
    `unit` VARCHAR(8) NOT NULL DEFAULT 'PERCENT',
    `minPaymentAmount` BIGINT,
    `startDate` BIGINT,
    `expiryDate` BIGINT,
    `redeemLimit` INT,
    `customerRedeemLimit` INT,
    `usedCount` INT NOT NULL DEFAULT 0,
    `status` VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    `createdAt` BIGINT(20) NOT NULL,
    `updatedAt` BIGINT(20) NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `unique_code` (`code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Market coupons table.';

CREATE TABLE IF NOT EXISTS `pano_market_creator_code` (
    `id` bigint NOT NULL AUTO_INCREMENT,
    `creator` VARCHAR(64) NOT NULL,
    `code` VARCHAR(64) NOT NULL,
    `discount` BIGINT NOT NULL,
    `unit` VARCHAR(8) NOT NULL DEFAULT 'PERCENT',
    `commissionPercent` BIGINT NOT NULL DEFAULT 0,
    `startDate` BIGINT,
    `expiryDate` BIGINT,
    `redeemLimit` INT,
    `usedCount` INT NOT NULL DEFAULT 0,
    `earnings` BIGINT NOT NULL DEFAULT 0,
    `status` VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    `createdAt` BIGINT(20) NOT NULL,
    `updatedAt` BIGINT(20) NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `unique_code` (`code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Market creator codes table.';

CREATE TABLE IF NOT EXISTS `pano_market_discount` (
    `id` bigint NOT NULL AUTO_INCREMENT,
    `name` VARCHAR(255) NOT NULL,
    `value` BIGINT NOT NULL,
    `unit` VARCHAR(8) NOT NULL DEFAULT 'PERCENT',
    `minPaymentAmount` BIGINT,
    `scope` VARCHAR(16) NOT NULL DEFAULT 'ALL',
    `productIds` MEDIUMTEXT,
    `categoryIds` MEDIUMTEXT,
    `startDate` BIGINT,
    `expiryDate` BIGINT,
    `usageLimit` INT,
    `usedCount` INT NOT NULL DEFAULT 0,
    `status` VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    `createdAt` BIGINT(20) NOT NULL,
    `updatedAt` BIGINT(20) NOT NULL,
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Market automatic discounts table.';

CREATE TABLE IF NOT EXISTS `pano_market_gift` (
    `id` bigint NOT NULL AUTO_INCREMENT,
    `code` VARCHAR(64) NOT NULL,
    `type` VARCHAR(16) NOT NULL,
    `productId` bigint,
    `creditAmount` BIGINT,
    `productIds` MEDIUMTEXT,
    `status` VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    `startDate` BIGINT,
    `expiryDate` BIGINT,
    `createdAt` BIGINT(20) NOT NULL,
    `updatedAt` BIGINT(20) NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `unique_code` (`code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Market gift codes table.';

CREATE TABLE IF NOT EXISTS `pano_market_order` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `userId` bigint,
  `playerUsername` VARCHAR(64) NOT NULL,
  `totalPrice` BIGINT NOT NULL,
  `currency` VARCHAR(8) NOT NULL DEFAULT 'TRY',
  `paymentMethodId` VARCHAR(64) NOT NULL DEFAULT '',
  `paymentLabel` VARCHAR(255) NOT NULL DEFAULT '',
  `status` VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  `createdAt` BIGINT(20) NOT NULL,
  `updatedAt` BIGINT(20) NOT NULL,
  `exchangeRate` DOUBLE,
  PRIMARY KEY (`id`),
  INDEX (`userId`),
  INDEX (`status`, `createdAt`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Market order table.';

CREATE TABLE IF NOT EXISTS `pano_market_order_item` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `orderId` bigint NOT NULL,
  `productId` bigint,
  `productName` VARCHAR(255) NOT NULL,
  `quantity` INT NOT NULL DEFAULT 1,
  `unitPrice` BIGINT NOT NULL,
  `createdAt` BIGINT(20) NOT NULL,
  `updatedAt` BIGINT(20) NOT NULL,
  PRIMARY KEY (`id`),
  INDEX (`orderId`),
  INDEX (`productId`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Market order item table.';

CREATE TABLE IF NOT EXISTS `pano_market_payment_method` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `methodId` VARCHAR(64) NOT NULL,
  `enabled` tinyint(1) NOT NULL DEFAULT 0,
  `settings` MEDIUMTEXT,
  `createdAt` BIGINT(20) NOT NULL,
  `updatedAt` BIGINT(20) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `unique_method_id` (`methodId`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Market payment methods table.';

CREATE TABLE IF NOT EXISTS `pano_market_product` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `slug` VARCHAR(255) NOT NULL,
  `name` VARCHAR(255) NOT NULL,
  `description` MEDIUMTEXT,
  `categoryId` bigint,
  `price` BIGINT NOT NULL DEFAULT 0,
  `creditPrice` BIGINT NOT NULL DEFAULT 0,
  `stock` INT,
  `requiredProducts` MEDIUMTEXT,
  `requireOnlyOne` TINYINT(1) NOT NULL DEFAULT 0,
  `requiredPermission` VARCHAR(255),
  `status` VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  `featured` TINYINT(1) NOT NULL DEFAULT 0,
  `durationType` VARCHAR(16) NOT NULL DEFAULT 'LIFETIME',
  `durationStart` BIGINT,
  `durationExpiry` BIGINT,
  `priority` INT NOT NULL DEFAULT 0,
  `icon` VARCHAR(64) NOT NULL DEFAULT 'fa-box',
  `imageFileName` VARCHAR(255),
  `actions` MEDIUMTEXT,
  `createdAt` BIGINT(20) NOT NULL,
  `updatedAt` BIGINT(20) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `unique_slug` (`slug`),
  INDEX (`categoryId`),
  INDEX (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Market product table.';

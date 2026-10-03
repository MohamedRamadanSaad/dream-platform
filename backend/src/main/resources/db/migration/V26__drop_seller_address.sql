-- V26: the seller address is not published anywhere (owner's decision): the setting added in V22 is removed.
DELETE FROM app_settings WHERE key = 'brand.legal_address';

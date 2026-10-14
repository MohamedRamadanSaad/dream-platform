-- V35: the receiving InstaPay account becomes the business account (Dream Group), replacing the owner's test account.
-- Only rows still holding the V34 test values change: an account the interpreter already set by hand is kept.
UPDATE app_settings SET value = 'vcbow1694@instapay'
 WHERE key = 'payments.instapay.address' AND value = 'mohamedramadansaad@instapay';
UPDATE app_settings SET value = 'https://ipn.eg/S/vcbow1694/instapay/3F76CH'
 WHERE key = 'payments.instapay.link' AND value = 'https://ipn.eg/S/mohamedramadansaad/instapay/2MKvsT';

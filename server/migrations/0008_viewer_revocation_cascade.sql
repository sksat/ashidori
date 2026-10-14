-- The cascade of revoking an approving device (docs/design/browser-pairing-v1.md,
-- 承認端末の失効) as a trigger, so that it also applies when the Worker of
-- migration 0006 revokes the device (it serves for a while after the deploy
-- applied 0007 and this migration, and again after a rollback). That Worker
-- only sets devices.revoked_at; the trigger then revokes the viewers the
-- device approved and aborts the attempts it claimed and has not finished,
-- in the same statement (so in the same transaction).
--
-- Backward compatible: no column changes, and both Workers write only what
-- they wrote before. The current Worker's revokeDevice does the same updates
-- itself; they are then no-ops. Authentication and the grant retrieval also
-- check the approving device, so a viewer whose cascade was missed (e.g. a
-- row changed by hand) is refused all the same.

CREATE TRIGGER devices_revoke_cascade AFTER UPDATE OF revoked_at ON devices
WHEN NEW.revoked_at IS NOT NULL AND NEW.kind = 'device'
BEGIN
  UPDATE devices SET revoked_at = NEW.revoked_at
    WHERE granted_by = NEW.id AND kind = 'viewer' AND revoked_at IS NULL;
  UPDATE viewer_pairings SET state = 'aborted', aborted_at = NEW.revoked_at
    WHERE claimed_by = NEW.id AND state NOT IN ('active', 'aborted');
END;

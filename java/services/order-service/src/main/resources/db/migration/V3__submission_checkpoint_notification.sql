-- A transient wake-up hint only; submission state remains the durable recovery source.
-- PostgreSQL delivers NOTIFY only if this transaction commits. A trigger covers every writer,
-- including failed preparation and cancellation, and CAS no-ops cannot emit a notification.
CREATE FUNCTION notify_order_submission_changed() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM pg_notify('order_submission_changed', NEW.checkout_token::text);
    RETURN NEW;
END;
$$;

CREATE TRIGGER order_submission_checkpoint_changed
AFTER UPDATE OF state ON order_checkout_submissions
FOR EACH ROW
WHEN (OLD.state = 'PREPARING' AND NEW.state IS DISTINCT FROM OLD.state)
EXECUTE FUNCTION notify_order_submission_changed();

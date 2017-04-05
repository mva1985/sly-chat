CREATE TABLE IF NOT EXISTS attachments (
    conversation_id TEXT NOT NULL,

    message_id TEXT NOT NULL,

    n INTEGER NOT NULL,

    -- name given by the sender (for display purposes in message logs)
    display_name TEXT NOT NULL,

    -- indicates whether or not the attachment should be inlined in the message (for things like images)
    is_inline BOOLEAN NOT NULL,

    -- may be no longer be valid, so we can't use a foreign key here
    file_id TEXT NOT NULL,

    PRIMARY KEY (conversation_id, message_id, n)
);


(disable-warning
 {:linter :wrong-arity
  :function-symbol 'amazonica.aws.sqs/send-message
  :arglists-for-linting
  '([creds sqs-queue trigger])
  :reason "defun creates calls to and with only 1 argument."})
# Ktor-HttpClient-chunked-encoding

Minimal example of Ktor HttpClient bug when streaming malformed HTTP responses with chunked encoding.

The example sets up two raw TCP servers, one which always answers with a valid (good) HTTP response,
and one that answers with a truncated (bad) HTTP response. The example shows that after an attempt
to stream from the bad TCP server, the client can no longer make requests to the good server either. 
It resides in a broken state.

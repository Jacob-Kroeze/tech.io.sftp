# tech.io.sftp
sftp bindings for tech.io (https://github.com/techascent/tech.io)

## Usage
Configuration is the most work when using these bindings, but simple usage is the reward!

For configuration, this library uses `tech.config` (https://github.com/techascent/tech.config).

Export environment variables or set up azure key vault. On load, the sftp provider looks for variables prefixed with "tech-sftp". Variables that are urls are made available to the sftp-client as namespaced keys. Use url parameters as arguments passed to the sftp client.

Example: 
`export TECH_SFTP_MY_SECRET_URL="sftp://example.net?username=myuser&password=mypass"`

## Multiple Users, One Host
If you have have access to two users on the same sftp host, pass username like this:
```
export TECH_SFTP_COMPLICATED="sftp://user1@host.net?username=user1&password=pass1"
export TECH_SFTP_COMPLICATED2="sftp://user2@host.net?username=user2&password=pass2"
```

"...user1@..." is only used to properly namespace and lookup the arguments needed for the host sftp. The sftp client sees something like this map and filters for credentials whose namespace matches the current host:
```
{:user1@host.net/username "user1"
 :user1@host.net/password "pass1"
 :user2@host.net/username "user2"
 :user2@host.net/password "pass2"}
```
## Azure Key Vault
Azure key vault requires environment set for
```
AZURE_TENANT_ID
AZURE_CLIENT_ID
AZURE_CLIENT_SECRET
```
When using Azure key vault, listed keys will also be read into configuration, along with secrets named starting with "tech-sftp"
Example: `:key1, :key2, and :key3` will be available in configuration.
```
(slurp "resource/tech-sftp-config.edn")
;; =>  {:tech-sftp-vault-keys [:key1 :key2 :key3]}
```

To use a private-key to authenticate, pass a url argument naming the key that contains an RSA key string. The sftp client looks for the full key in the environment, something like this:
`{:private-key (config/get-config :my-private-key)}`


Finally, use `tech.io` Simple!
```clojure
(require '[tech.v3.io.sftp] '[tech.v3.io :as t.io])

(t.io/copy "/my/local/file.txt" "sftp://my.sftpserver.net/path/to/file.txt")

```

*Note: when changing configuration `tech.v3.io.sftp` must be reloaded because variables are only read in at start up*.

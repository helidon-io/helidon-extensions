# io.<wbr>helidon.<wbr>common.<wbr>pki.<wbr>Keystore<wbr>Keys

## Description

Resources from a java keystore (PKCS12, JKS etc.)

## Configuration options


<table>
<thead>
<tr>
<th>Key</th>
<th>Type</th>
<th>Default</th>
<th>Description</th>
</tr>
</thead>
<tbody>
<tr>
<td>
<code>cert.<wbr>alias</code>
</td>
<td>
<code>String</code>
</td>
<td>
</td>
<td>Alias of X.509 certificate of public key</td>
</tr>
<tr>
<td>
<a id="resource"></a>
<a href="io.helidon.common.configurable.Resource.md">
<code>resource</code>
</a>
</td>
<td>
<code>Resource</code>
</td>
<td>
</td>
<td>Keystore resource definition</td>
</tr>
<tr>
<td>
<code>cert-<wbr>chain.<wbr>alias</code>
</td>
<td>
<code>String</code>
</td>
<td>
</td>
<td>Alias of an X.509 chain</td>
</tr>
<tr>
<td>
<code>trust-<wbr>store</code>
</td>
<td>
<code>Boolean</code>
</td>
<td>
<code>false</code>
</td>
<td>If you want to build a trust store, call this method to add all certificates present in the keystore to certificate list</td>
</tr>
<tr>
<td>
<code>key.<wbr>alias</code>
</td>
<td>
<code>String</code>
</td>
<td>
</td>
<td>Alias of the private key in the keystore</td>
</tr>
<tr>
<td>
<code>passphrase</code>
</td>
<td>
<code>String</code>
</td>
<td>
</td>
<td>Pass-phrase of the keystore (supported with JKS and PKCS12 keystores)</td>
</tr>
<tr>
<td>
<code>key.<wbr>passphrase</code>
</td>
<td>
<code>String</code>
</td>
<td>
</td>
<td>Pass-phrase of the key in the keystore (used for private keys)</td>
</tr>
<tr>
<td>
<code>type</code>
</td>
<td>
<code>String</code>
</td>
<td>
<code>PKCS12</code>
</td>
<td>Set type of keystore</td>
</tr>
</tbody>
</table>



---

See the [manifest](manifest.md) for all available types.

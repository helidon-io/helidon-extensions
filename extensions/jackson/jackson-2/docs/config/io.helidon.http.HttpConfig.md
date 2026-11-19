# io.<wbr>helidon.<wbr>http.<wbr>Http<wbr>Config

## Description

Common configuration of HTTP protocol, regardless of its version

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
<code>validate-<wbr>response-<wbr>headers</code>
</td>
<td>
<code>Boolean</code>
</td>
<td>
<code>true</code>
</td>
<td>Whether to validate response headers</td>
</tr>
<tr>
<td>
<code>max-<wbr>buffered-<wbr>entity-<wbr>size</code>
</td>
<td>
<code>Size</code>
</td>
<td>
<code>1 Mi<wbr>B</code>
</td>
<td>Configure the maximum size of a decoded entity materialized in memory, including <code>byte[]</code>, strings, forms, JSON, and explicit <code>io.<wbr>helidon.<wbr>http.<wbr>media.<wbr>Readable<wbr>Entity.<wbr>buffer(<wbr>)</code> calls</td>
</tr>
<tr>
<td>
<a id="log"></a>
<a href="io.helidon.http.HttpLogConfig.md">
<code>log</code>
</a>
</td>
<td>
<code>Http<wbr>LogConfig</code>
</td>
<td>
</td>
<td>HTTP Log configuration</td>
</tr>
<tr>
<td>
<code>validate-<wbr>request-<wbr>headers</code>
</td>
<td>
<code>Boolean</code>
</td>
<td>
<code>true</code>
</td>
<td>Whether to validate request headers</td>
</tr>
<tr>
<td>
<code>max-<wbr>headers-<wbr>size</code>
</td>
<td>
<code>Integer</code>
</td>
<td>
<code>16384</code>
</td>
<td>Configure the maximum allowed headers size, which must be greater than <code>0</code></td>
</tr>
</tbody>
</table>



---

See the [manifest](manifest.md) for all available types.

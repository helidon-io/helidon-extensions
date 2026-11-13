# io.<wbr>helidon.<wbr>common.<wbr>configurable.<wbr>Resource

## Description

Configuration of a resource

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
<code>path</code>
</td>
<td>
<code>Path</code>
</td>
<td>
</td>
<td>Resource is located on filesystem</td>
</tr>
<tr>
<td>
<code>proxy-<wbr>port</code>
</td>
<td>
<code>Integer</code>
</td>
<td>
<code>80</code>
</td>
<td>Port of the proxy when using URI</td>
</tr>
<tr>
<td>
<code>resource-<wbr>path</code>
</td>
<td>
<code>String</code>
</td>
<td>
</td>
<td>Resource is located on classpath</td>
</tr>
<tr>
<td>
<code>use-<wbr>proxy</code>
</td>
<td>
<code>Boolean</code>
</td>
<td>
<code>true</code>
</td>
<td>Whether to use proxy</td>
</tr>
<tr>
<td>
<code>description</code>
</td>
<td>
<code>String</code>
</td>
<td>
</td>
<td>Description of this resource when configured through plain text or binary</td>
</tr>
<tr>
<td>
<code>proxy-<wbr>host</code>
</td>
<td>
<code>String</code>
</td>
<td>
</td>
<td>Host of the proxy when using URI</td>
</tr>
<tr>
<td>
<code>uri</code>
</td>
<td>
<code>URI</code>
</td>
<td>
</td>
<td>Resource is available on a <code>java.<wbr>net.<wbr>URI</code></td>
</tr>
<tr>
<td>
<code>content</code>
</td>
<td>
<code>String</code>
</td>
<td>
</td>
<td>Binary content of the resource (base64 encoded)</td>
</tr>
<tr>
<td>
<code>content-<wbr>plain</code>
</td>
<td>
<code>String</code>
</td>
<td>
</td>
<td>Plain content of the resource (text)</td>
</tr>
</tbody>
</table>



---

See the [manifest](manifest.md) for all available types.

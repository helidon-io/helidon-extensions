# io.<wbr>helidon.<wbr>http.<wbr>Http<wbr>LogConfig

## Description

Configuration of logging of the HTTP layer

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
<code>send-<wbr>log</code>
</td>
<td>
<code>Boolean</code>
</td>
<td>
<code>true</code>
</td>
<td>Logging of sent packets</td>
</tr>
<tr>
<td>
<code>logger-<wbr>name</code>
</td>
<td>
<code>String</code>
</td>
<td>
</td>
<td>Base name of the logger to use when logging receive and send packets</td>
</tr>
<tr>
<td>
<code>log-<wbr>safe-<wbr>headers</code>
</td>
<td>
<code>List&lt;<wbr>Http<wbr>Config<wbr>Custom<wbr>Methods&gt;</code>
</td>
<td>
</td>
<td>Header names whose values can be logged at debug level, except sensitive names that are always redacted</td>
</tr>
<tr>
<td>
<code>recv-<wbr>log</code>
</td>
<td>
<code>Boolean</code>
</td>
<td>
<code>true</code>
</td>
<td>Logging of received packets</td>
</tr>
</tbody>
</table>



---

See the [manifest](manifest.md) for all available types.

/*
 * Copyright (c) 2026 Oracle and/or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

"use strict";

const form = document.getElementById("chat-form");
const question = document.getElementById("question");
const send = document.getElementById("send");
const reset = document.getElementById("reset");
const conversation = document.getElementById("conversation");
const status = document.getElementById("status");
let summary = "";

function appendMessage(role, text) {
    const item = document.createElement("li");
    item.className = role;
    const heading = document.createElement("strong");
    heading.textContent = role === "user" ? "You" : "Helidon Assistant";
    const content = document.createElement("p");
    content.textContent = text;
    item.append(heading, content);
    conversation.append(item);
}

form.addEventListener("submit", async (event) => {
    event.preventDefault();
    const message = question.value.trim();
    if (!message || send.disabled) {
        return;
    }
    send.disabled = true;
    reset.disabled = true;
    status.textContent = "Reading the documentation…";
    appendMessage("user", message);
    try {
        const response = await fetch("chat", {
            method: "POST",
            headers: {"Content-Type": "application/json", "Accept": "application/json"},
            body: JSON.stringify({message, summary})
        });
        if (!response.ok) {
            throw new Error("Request failed (HTTP " + response.status + "). Check the server log and model configuration.");
        }
        const answer = await response.json();
        if (typeof answer.message !== "string" || typeof answer.summary !== "string") {
            throw new Error("The assistant returned an invalid response.");
        }
        appendMessage("assistant", answer.message);
        summary = answer.summary;
        question.value = "";
        status.textContent = "";
    } catch (error) {
        status.textContent = error.message;
    } finally {
        send.disabled = false;
        reset.disabled = false;
        question.focus();
    }
});

reset.addEventListener("click", () => {
    summary = "";
    conversation.replaceChildren();
    status.textContent = "";
    question.value = "";
    question.focus();
});

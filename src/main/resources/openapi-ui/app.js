(function () {
  "use strict";

  var METHODS = ["get", "post", "put", "patch", "delete", "head", "options", "trace"];

  var TYPE_MAP = {
    "integer:int64": "Long",
    "integer:int32": "Int",
    "integer:": "Int",
    "number:double": "Double",
    "number:float": "Float",
    "number:": "Double",
    "string:date-time": "LocalDateTime",
    "string:date": "LocalDate",
    "string:time": "LocalTime",
    "string:uuid": "UUID",
    "string:byte": "ByteArray",
    "string:binary": "MultipartFile",
    "string:": "String",
    "boolean:": "Boolean",
  };

  var state = {
    config: { docsUrl: "/v3/api-docs", tryItOut: true, title: null, headers: {}, mcpName: "openapi-docs" },
    spec: null,
    entries: [],
    filter: "",
    server: "",
    openGroups: {},
    auth: load("openapi-ui.auth", {}),
    headers: load("openapi-ui.headers", null),
    cookies: load("openapi-ui.cookies", null),
  };

  function load(key, fallback) {
    try {
      var raw = localStorage.getItem(key);
      return raw ? JSON.parse(raw) : fallback;
    } catch (error) {
      return fallback;
    }
  }

  function save(key, value) {
    try {
      localStorage.setItem(key, JSON.stringify(value));
    } catch (error) {
      void error;
    }
  }

  /* ---------------- security & headers ---------------- */

  function securitySchemes() {
    return (state.spec.components && state.spec.components.securitySchemes) || {};
  }

  function isCookieScheme(scheme) {
    return scheme.type === "apiKey" && scheme["in"] === "cookie";
  }

  function manualSchemeNames() {
    var schemes = securitySchemes();
    return Object.keys(schemes).filter(function (name) { return !isCookieScheme(schemes[name]); });
  }

  function cookieSchemeNames() {
    var schemes = securitySchemes();
    return Object.keys(schemes)
      .filter(function (name) { return isCookieScheme(schemes[name]); })
      .map(function (name) { return schemes[name].name; });
  }

  function requirementsOf(entry) {
    var requirements = entry.operation.security || state.spec.security || [];
    var names = [];
    requirements.forEach(function (requirement) {
      Object.keys(requirement).forEach(function (name) {
        if (names.indexOf(name) < 0) names.push(name);
      });
    });
    return names;
  }

  function authContributions() {
    var schemes = securitySchemes();
    var result = { headers: {}, query: {}, cookies: {} };

    Object.keys(schemes).forEach(function (name) {
      var scheme = schemes[name];
      var value = state.auth[name];
      if (!value) return;

      if (scheme.type === "http") {
        var kind = (scheme.scheme || "").toLowerCase();
        if (kind === "basic") result.headers.Authorization = "Basic " + value;
        else result.headers.Authorization = (kind ? kind.charAt(0).toUpperCase() + kind.slice(1) : "Bearer") + " " + value;
        return;
      }
      if (scheme.type === "oauth2" || scheme.type === "openIdConnect") {
        result.headers.Authorization = "Bearer " + value;
        return;
      }
      if (scheme.type === "apiKey") {
        if (scheme["in"] === "header") result.headers[scheme.name] = value;
        else if (scheme["in"] === "query") result.query[scheme.name] = value;
        else if (scheme["in"] === "cookie") result.cookies[scheme.name] = value;
      }
    });
    return result;
  }

  function rowsToMap(rows) {
    var result = {};
    (rows || []).forEach(function (row) {
      if (row.enabled !== false && row.name) result[row.name] = row.value;
    });
    return result;
  }

  function customHeaders() {
    return rowsToMap(state.headers);
  }

  function customCookies() {
    return rowsToMap(state.cookies);
  }

  function defaultRows(source) {
    return Object.keys(source || {}).map(function (name) {
      return { name: name, value: source[name], enabled: true };
    });
  }

  function applyCookies(cookies) {
    var names = Object.keys(cookies || {});
    if (!names.length) return false;
    var sameOrigin = !state.server || state.server.indexOf(location.origin) === 0 || state.server.charAt(0) === "/";
    if (!sameOrigin) return false;
    names.forEach(function (name) {
      document.cookie = name + "=" + encodeURIComponent(cookies[name]) + "; path=/";
    });
    return true;
  }

  /* ---------------- dom ---------------- */

  function h(tag, attrs, children) {
    var node = document.createElement(tag);
    if (attrs) {
      Object.keys(attrs).forEach(function (key) {
        var value = attrs[key];
        if (value === null || value === undefined || value === false) return;
        if (key === "class") node.className = value;
        else if (key === "html") node.innerHTML = value;
        else if (key === "text") node.textContent = value;
        else if (key.slice(0, 2) === "on") node.addEventListener(key.slice(2).toLowerCase(), value);
        else if (key === "dataset") Object.keys(value).forEach(function (k) { node.dataset[k] = value[k]; });
        else node.setAttribute(key, value);
      });
    }
    append(node, children);
    return node;
  }

  function append(node, children) {
    if (children === null || children === undefined || children === false) return;
    if (Array.isArray(children)) {
      children.forEach(function (child) { append(node, child); });
      return;
    }
    node.appendChild(children instanceof Node ? children : document.createTextNode(String(children)));
  }

  function clear(node) {
    while (node.firstChild) node.removeChild(node.firstChild);
    return node;
  }

  function escapeHtml(value) {
    return String(value).replace(/[&<>"']/g, function (ch) {
      return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[ch];
    });
  }

  /* ---------------- markdown ---------------- */

  var CODE_RULES = {
    json: [
      [/(&quot;[^&]*?&quot;)(\s*:)/g, '<span class="tok-key">$1</span>$2'],
      [/:\s(&quot;.*?&quot;)/g, ': <span class="tok-str">$1</span>'],
      [/:\s(-?\d+\.?\d*)/g, ': <span class="tok-num">$1</span>'],
      [/:\s(true|false|null)/g, ': <span class="tok-lit">$1</span>'],
    ],
    bash: [
      [/^(curl|http|npm|gradle|kubectl|docker)\b/gm, '<span class="tok-fn">$1</span>'],
      [/(\s)(-{1,2}[A-Za-z][\w-]*)/g, '$1<span class="tok-flag">$2</span>'],
      [/(&#39;[^&]*?&#39;)/g, '<span class="tok-str">$1</span>'],
    ],
    code: [
      [/(\/\/[^\n]*)/g, '<span class="tok-comment">$1</span>'],
      [/(&quot;[^&]*?&quot;|&#39;[^&]*?&#39;)/g, '<span class="tok-str">$1</span>'],
      [/\b(val|var|fun|class|interface|data|object|return|if|else|when|for|while|import|package|const|let|await|async|function|type|export|new|null|true|false|suspend|private|override)\b/g,
        '<span class="tok-kw">$1</span>'],
      [/\b(\d+\.?\d*)\b/g, '<span class="tok-num">$1</span>'],
    ],
  };

  function highlightCode(source, language) {
    var html = escapeHtml(source);
    var rules = language === "json" ? CODE_RULES.json
      : /^(bash|sh|shell|curl|console)$/.test(language) ? CODE_RULES.bash
        : language ? CODE_RULES.code : [];
    rules.forEach(function (rule) { html = html.replace(rule[0], rule[1]); });
    return html;
  }

  function inlineMarkdown(text) {
    return escapeHtml(text)
      .replace(/`([^`]+)`/g, "<code>$1</code>")
      .replace(/~~([^~]+)~~/g, "<del>$1</del>")
      .replace(/\*\*([^*]+)\*\*/g, "<strong>$1</strong>")
      .replace(/(^|[^*])\*([^*\n]+)\*/g, "$1<em>$2</em>")
      .replace(/\[([^\]]+)\]\((https?:[^)\s]+)\)/g, '<a href="$2" target="_blank" rel="noreferrer">$1</a>')
      .replace(/(^|[\s(])((?:https?:\/\/)[^\s<)]+)/g, '$1<a href="$2" target="_blank" rel="noreferrer">$2</a>')
      .replace(/ {2}$/, "<br>");
  }

  function renderList(items) {
    var html = "";
    var stack = [];

    items.forEach(function (item) {
      while (stack.length && item.depth < stack[stack.length - 1].depth) {
        html += "</li></" + stack.pop().tag + ">";
      }
      if (!stack.length || item.depth > stack[stack.length - 1].depth) {
        html += "<" + item.tag + (item.task ? ' class="md-tasks"' : "") + ">";
        stack.push({ tag: item.tag, depth: item.depth });
      } else {
        html += "</li>";
      }
      var text = item.task
        ? '<span class="md-task' + (item.checked ? " is-done" : "") + '">' + (item.checked ? "☑" : "☐") + "</span> " + inlineMarkdown(item.text)
        : inlineMarkdown(item.text);
      html += "<li>" + text;
    });

    while (stack.length) html += "</li></" + stack.pop().tag + ">";
    return html;
  }

  function renderTable(rows, align) {
    var head = rows[0] || [];
    var body = rows.slice(1);
    var cellStyle = function (index) {
      return align[index] ? ' style="text-align:' + align[index] + '"' : "";
    };
    return '<div class="md-table"><table><thead><tr>' +
      head.map(function (cell, index) { return "<th" + cellStyle(index) + ">" + inlineMarkdown(cell) + "</th>"; }).join("") +
      "</tr></thead><tbody>" +
      body.map(function (row) {
        return "<tr>" + row.map(function (cell, index) {
          return "<td" + cellStyle(index) + ">" + inlineMarkdown(cell) + "</td>";
        }).join("") + "</tr>";
      }).join("") +
      "</tbody></table></div>";
  }

  function splitRow(line) {
    return line.replace(/^\s*\|/, "").replace(/\|\s*$/, "").split("|").map(function (cell) { return cell.trim(); });
  }

  function markdown(source) {
    if (!source) return "";
    var lines = String(source).replace(/\r\n/g, "\n").split("\n");
    var out = [];
    var listItems = [];
    var paragraph = [];
    var table = null;
    var code = null;

    function flushParagraph() {
      if (!paragraph.length) return;
      out.push("<p>" + inlineMarkdown(paragraph.join(" ")) + "</p>");
      paragraph = [];
    }

    function flushList() {
      if (!listItems.length) return;
      out.push(renderList(listItems));
      listItems = [];
    }

    function flushTable() {
      if (!table) return;
      out.push(renderTable(table.rows, table.align));
      table = null;
    }

    function flushAll() {
      flushParagraph();
      flushList();
      flushTable();
    }

    lines.forEach(function (line, index) {
      var fence = line.match(/^\s*```(\w*)\s*$/);
      if (fence) {
        if (code) {
          out.push(codeBlockHtml(code.lines.join("\n"), code.lang));
          code = null;
        } else {
          flushAll();
          code = { lines: [], lang: fence[1] || "" };
        }
        return;
      }
      if (code) {
        code.lines.push(line);
        return;
      }

      if (/^\s*(-{3,}|\*{3,}|_{3,})\s*$/.test(line)) {
        flushAll();
        out.push("<hr>");
        return;
      }

      var heading = line.match(/^(#{1,6})\s+(.*)$/);
      if (heading) {
        flushAll();
        var level = Math.min(heading[1].length + 2, 6);
        out.push("<h" + level + ">" + inlineMarkdown(heading[2]) + "</h" + level + ">");
        return;
      }

      if (/^\s*\|.*\|\s*$/.test(line)) {
        var next = lines[index + 1] || "";
        if (!table && /^\s*\|?[\s:-]*-[\s|:-]*$/.test(next)) {
          flushParagraph();
          flushList();
          table = { rows: [splitRow(line)], align: [], pending: true };
          return;
        }
        if (table) {
          if (table.pending && /^\s*\|?[\s:-]*-[\s|:-]*$/.test(line)) {
            table.align = splitRow(line).map(function (cell) {
              if (/^:.*:$/.test(cell)) return "center";
              if (/:$/.test(cell)) return "right";
              return "";
            });
            table.pending = false;
            return;
          }
          table.rows.push(splitRow(line));
          return;
        }
      } else if (table) {
        flushTable();
      }

      var bullet = line.match(/^(\s*)[-*+]\s+(.*)$/);
      var ordered = line.match(/^(\s*)\d+[.)]\s+(.*)$/);
      if (bullet || ordered) {
        flushParagraph();
        var match = bullet || ordered;
        var text = match[2];
        var task = text.match(/^\[( |x|X)\]\s+(.*)$/);
        listItems.push({
          tag: bullet ? "ul" : "ol",
          depth: Math.floor(match[1].replace(/\t/g, "  ").length / 2),
          text: task ? task[2] : text,
          task: !!task,
          checked: task ? task[1].toLowerCase() === "x" : false,
        });
        return;
      }

      var quote = line.match(/^>\s?(.*)$/);
      if (quote) {
        flushAll();
        out.push("<blockquote>" + inlineMarkdown(quote[1]) + "</blockquote>");
        return;
      }

      if (!line.trim()) {
        flushAll();
        return;
      }
      paragraph.push(line.trim());
    });

    if (code) out.push(codeBlockHtml(code.lines.join("\n"), code.lang));
    flushAll();
    return out.join("");
  }

  function codeBlockHtml(source, language) {
    return '<div class="codeblock">' +
      '<div class="codeblock__head">' +
      '<span class="codeblock__lang">' + escapeHtml(language || "text") + "</span>" +
      '<button class="codeblock__copy" type="button">Copy</button>' +
      "</div>" +
      '<pre class="codeblock__body"><code>' + highlightCode(source, language) + "</code></pre>" +
      "</div>";
  }

  function enhanceMarkdown(root) {
    root.querySelectorAll(".codeblock__copy").forEach(function (button) {
      if (button.dataset.wired) return;
      button.dataset.wired = "1";
      button.addEventListener("click", function () {
        var code = button.closest(".codeblock").querySelector("code");
        copy(code.textContent, button);
        button.textContent = "Copied";
        setTimeout(function () { button.textContent = "Copy"; }, 1400);
      });
    });
    return root;
  }

  /* ---------------- spec helpers ---------------- */

  function resolve(schema, seen) {
    if (!schema) return null;
    if (!schema.$ref) return schema;
    if (seen && seen.indexOf(schema.$ref) >= 0) return { type: "object" };
    var path = schema.$ref.replace(/^#\//, "").split("/");
    var target = state.spec;
    for (var i = 0; i < path.length && target; i += 1) target = target[decodeURIComponent(path[i])];
    return target || null;
  }

  function refName(schema) {
    return schema && schema.$ref ? schema.$ref.split("/").pop() : null;
  }

  function merged(schema, seen) {
    var resolved = resolve(schema, seen);
    if (!resolved) return null;
    if (!resolved.allOf) return resolved;
    var result = { type: "object", properties: {}, required: [] };
    resolved.allOf.forEach(function (part) {
      var piece = merged(part, seen);
      if (!piece) return;
      Object.assign(result.properties, piece.properties || {});
      if (piece.required) result.required = result.required.concat(piece.required);
      if (piece.description && !result.description) result.description = piece.description;
    });
    return result;
  }

  function kotlinType(schema, seen) {
    var trail = seen || [];
    var resolved = merged(schema, trail);
    if (!resolved) return "Any";
    var name = refName(schema);
    if (schema && schema.$ref) trail = trail.concat([schema.$ref]);

    if (resolved.enum) return name || "String";
    if (resolved.type === "array") return "List<" + kotlinType(resolved.items, trail) + ">";
    if (resolved.type === "object" || resolved.properties) {
      if (name) return name;
      if (resolved.additionalProperties) return "Map<String, " + kotlinType(resolved.additionalProperties, trail) + ">";
      return "Any";
    }
    if (name && !resolved.type) return name;
    return TYPE_MAP[(resolved.type || "") + ":" + (resolved.format || "")] ||
      TYPE_MAP[(resolved.type || "") + ":"] ||
      (resolved.type || "Any");
  }

  function enumInfo(schema) {
    var resolved = merged(schema, []);
    if (!resolved || !resolved.enum) return null;
    return { values: resolved.enum, descriptions: resolved["x-enum-descriptions"] || null };
  }

  function withoutEnumLines(text) {
    if (!text) return "";
    return String(text)
      .split("\n")
      .filter(function (line) { return !/^\s*-\s+`[^`]+`\s*:/.test(line); })
      .join("\n")
      .trim();
  }

  function descriptionOf(param) {
    var schema = merged(param.schema, []) || {};
    var text = param.description || schema.description || "";
    return enumInfo(param.schema) ? withoutEnumLines(text) : text;
  }

  function sample(schema, seen) {
    var trail = seen || [];
    if (schema && schema.$ref && trail.indexOf(schema.$ref) >= 0) return null;
    var resolved = merged(schema, trail);
    if (!resolved) return null;
    if (schema && schema.$ref) trail = trail.concat([schema.$ref]);

    if (resolved.example !== undefined) return resolved.example;
    if (resolved.default !== undefined) return resolved.default;
    if (resolved.enum && resolved.enum.length) return resolved.enum[0];
    if (resolved.oneOf || resolved.anyOf) return sample((resolved.oneOf || resolved.anyOf)[0], trail);

    switch (resolved.type) {
      case "array": return [sample(resolved.items, trail)];
      case "integer": return 0;
      case "number": return 0;
      case "boolean": return true;
      case "string":
        if (resolved.format === "date-time") return "2026-01-01T09:00:00";
        if (resolved.format === "date") return "2026-01-01";
        if (resolved.format === "uuid") return "00000000-0000-0000-0000-000000000000";
        return "string";
      default: break;
    }

    if (resolved.properties) {
      var result = {};
      Object.keys(resolved.properties).forEach(function (key) {
        result[key] = sample(resolved.properties[key], trail);
      });
      return result;
    }
    if (resolved.additionalProperties) return {};
    return null;
  }

  function highlightJson(value) {
    var json = typeof value === "string" ? value : JSON.stringify(value, null, 2);
    if (json === undefined) return "";
    return escapeHtml(json)
      .replace(/&quot;([^&]*?)&quot;(\s*:)/g, '<span class="tok-key">&quot;$1&quot;</span>$2')
      .replace(/:\s&quot;(.*?)&quot;/g, ': <span class="tok-str">&quot;$1&quot;</span>')
      .replace(/:\s(-?\d+\.?\d*)/g, ': <span class="tok-num">$1</span>')
      .replace(/:\s(true|false|null)/g, ': <span class="tok-lit">$1</span>');
  }

  function parametersOf(entry) {
    return (entry.pathItem.parameters || [])
      .concat(entry.operation.parameters || [])
      .map(function (item) { return resolve(item, []); })
      .filter(Boolean);
  }

  function mediaTypesOf(holder) {
    if (!holder || !holder.content) return [];
    var keys = Object.keys(holder.content);
    return keys.sort(function (a, b) {
      return (b.indexOf("json") >= 0 ? 1 : 0) - (a.indexOf("json") >= 0 ? 1 : 0);
    });
  }

  function contentOf(holder, mediaType) {
    var types = mediaTypesOf(holder);
    if (!types.length) return null;
    var picked = mediaType && types.indexOf(mediaType) >= 0 ? mediaType : types[0];
    var media = holder.content[picked] || {};
    return {
      mediaType: picked,
      mediaTypes: types,
      schema: media.schema,
      examples: media.examples || null,
      example: media.example,
    };
  }

  function requestBodyOf(operation, mediaType) {
    var body = resolve(operation.requestBody, []);
    if (!body) return null;
    var content = contentOf(body, mediaType);
    if (!content) return null;
    return {
      mediaType: content.mediaType,
      mediaTypes: content.mediaTypes,
      schema: content.schema,
      examples: content.examples,
      example: content.example,
      required: !!body.required,
    };
  }

  function isMultipart(mediaType) {
    return String(mediaType || "").indexOf("multipart/") === 0;
  }

  function isFormUrlEncoded(mediaType) {
    return String(mediaType || "").indexOf("x-www-form-urlencoded") >= 0;
  }

  function isBinary(schema) {
    var resolved = merged(schema, []);
    return !!resolved && resolved.type === "string" && resolved.format === "binary";
  }

  function operationId(entry) {
    return entry.method + "-" + entry.path.replace(/[^\w]+/g, "-").replace(/^-|-$/g, "");
  }

  /* ---------------- copy ---------------- */

  function copy(value, button) {
    var done = function () {
      if (!button) return;
      button.classList.add("is-done");
      setTimeout(function () { button.classList.remove("is-done"); }, 1400);
    };
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(value).then(done, function () { fallbackCopy(value, done); });
      return;
    }
    fallbackCopy(value, done);
  }

  function fallbackCopy(value, done) {
    var area = h("textarea", { style: "position:fixed;opacity:0" });
    area.value = value;
    document.body.appendChild(area);
    area.select();
    try {
      document.execCommand("copy");
      done();
    } finally {
      area.remove();
    }
  }

  function copyButton(getValue, label) {
    var button = h("button", { class: "ghost-btn", type: "button", title: "Copy" }, [
      h("span", { class: "ghost-btn__icon", text: "⧉" }),
      label ? h("span", { text: label }) : null,
    ]);
    button.addEventListener("click", function (event) {
      event.preventDefault();
      event.stopPropagation();
      copy(getValue(), button);
    });
    return button;
  }

  /* ---------------- select ---------------- */

  function select(options, value, onChange) {
    var current = value;
    var root = h("div", { class: "select" });
    var valueNode = h("span", { class: "select__value" });
    var descNode = h("span", { class: "select__desc" });
    var button = h("button", { class: "select__button", type: "button" }, [
      valueNode, descNode, h("span", { class: "select__arrow", text: "▾" }),
    ]);
    var list = h("div", { class: "select__list" });
    var open = false;

    function sync() {
      var found = options.filter(function (option) { return option.value === current; })[0] || options[0];
      valueNode.textContent = found ? found.label : "";
      descNode.textContent = found && found.description ? found.description : "";
    }

    function close() {
      root.classList.remove("is-open");
      list.classList.remove("is-shown");
      setTimeout(function () { list.remove(); }, 160);
      open = false;
    }

    function show() {
      clear(list);
      options.forEach(function (option) {
        list.appendChild(h("button", {
          class: "select__option" + (option.value === current ? " is-selected" : ""),
          type: "button",
          onclick: function () {
            current = option.value;
            sync();
            close();
            if (onChange) onChange(current);
          },
        }, [
          h("span", { class: "select__value", text: option.label }),
          option.description ? h("span", { class: "select__desc", text: option.description }) : null,
        ]));
      });
      var rect = button.getBoundingClientRect();
      list.style.left = rect.left + "px";
      list.style.top = rect.bottom + 8 + "px";
      list.style.minWidth = rect.width + "px";
      document.body.appendChild(list);
      root.classList.add("is-open");
      requestAnimationFrame(function () { list.classList.add("is-shown"); });
      open = true;
    }

    button.addEventListener("click", function () { open ? close() : show(); });
    document.addEventListener("click", function (event) {
      if (open && !root.contains(event.target) && !list.contains(event.target)) close();
    }, true);
    window.addEventListener("scroll", function (event) {
      if (!open) return;
      if (event.target && event.target.nodeType === 1 && list.contains(event.target)) return;
      close();
    }, true);

    root.appendChild(button);
    sync();
    return { node: root, get: function () { return current; } };
  }

  /* ---------------- tabs ---------------- */

  function tabs(items) {
    var strip = h("div", { class: "tabs" });
    var body = h("div", { class: "tabs__body" });
    var buttons = [];

    function activate(index) {
      buttons.forEach(function (button, i) { button.classList.toggle("is-active", i === index); });
      clear(body);
      body.appendChild(items[index].render());
    }

    items.forEach(function (item, index) {
      var button = h("button", { class: "tabs__tab", type: "button", text: item.label });
      button.addEventListener("click", function () { activate(index); });
      buttons.push(button);
      strip.appendChild(button);
    });

    if (items.length) activate(0);
    return { strip: strip, body: body, activate: activate };
  }

  /* ---------------- prose blocks ---------------- */

  function parameterTable(params) {
    var grid = h("div", { class: "params" }, [
      h("div", { class: "params__head", text: "Type" }),
      h("div", { class: "params__head", text: "Name" }),
      h("div", { class: "params__head", text: "Category" }),
      h("div", { class: "params__head", text: "Description" }),
    ]);

    params.forEach(function (param) {
      var info = enumInfo(param.schema);
      var description = descriptionOf(param);
      grid.appendChild(h("div", { class: "params__cell params__type", text: kotlinType(param.schema) }));
      grid.appendChild(h("div", { class: "params__cell params__name" }, [
        param.name,
        param.required
          ? h("span", { class: "params__required", title: "required", text: "*" })
          : h("span", { class: "params__optional", title: "optional", text: "?" }),
      ]));
      grid.appendChild(h("div", { class: "params__cell params__category", text: param.in }));
      grid.appendChild(h("div", { class: "params__cell params__description" }, [
        description ? h("div", { class: "md", html: markdown(description) }) : null,
        info ? enumList(info) : null,
      ]));
    });
    return grid;
  }

  var ENUM_PREVIEW = 4;

  function enumList(info) {
    var described = !!info.descriptions;
    var grid = h("div", { class: "enum" + (described ? "" : " enum--inline") });
    var preview = info.values.slice(0, ENUM_PREVIEW);

    preview.forEach(function (value) {
      grid.appendChild(h("code", { class: "enum__value", text: value }));
      if (described) grid.appendChild(h("span", { class: "enum__desc", text: info.descriptions[value] || "" }));
    });

    var wrap = h("div", { class: "enum-wrap" }, grid);
    if (info.values.length > ENUM_PREVIEW) {
      var more = h("button", {
        class: "enum-more",
        type: "button",
        text: "+" + (info.values.length - ENUM_PREVIEW) + " more values",
      });
      more.addEventListener("click", function (event) {
        event.stopPropagation();
        openPicker(more, info, null, function () { closePicker(); });
      });
      wrap.appendChild(more);
    }
    return wrap;
  }

  /* ---------------- value picker ---------------- */

  var activePicker = null;
  var activeAnchor = null;

  function closePicker() {
    if (!activePicker) return;
    var node = activePicker;
    if (activeAnchor) activeAnchor.classList.remove("is-open");
    activePicker = null;
    activeAnchor = null;
    node.classList.remove("is-shown");
    setTimeout(function () { node.remove(); }, 150);
  }

  document.addEventListener("click", function (event) {
    if (!activePicker) return;
    if (activePicker.contains(event.target)) return;
    if (activeAnchor && activeAnchor.contains(event.target)) return;
    closePicker();
  }, true);
  window.addEventListener("scroll", function (event) {
    if (!activePicker) return;
    if (event.target && event.target.nodeType === 1 && activePicker.contains(event.target)) return;
    closePicker();
  }, true);
  document.addEventListener("keydown", function (event) {
    if (event.key === "Escape") closePicker();
  });

  function openPicker(anchor, info, current, onPick, multi) {
    if (activePicker && activeAnchor === anchor) {
      closePicker();
      return;
    }
    closePicker();
    var panel = h("div", { class: "picker" });
    var list = h("div", { class: "picker__list" });
    var selected = multi ? (current || []).slice() : current;
    var filterValue = "";

    function isSelected(value) {
      return multi ? selected.indexOf(value) >= 0 : value === selected;
    }

    function paint() {
      clear(list);
      var needle = filterValue.toLowerCase();
      var matched = info.values.filter(function (value) {
        var description = info.descriptions ? info.descriptions[value] || "" : "";
        return (value + " " + description).toLowerCase().indexOf(needle) >= 0;
      });
      if (!matched.length) {
        list.appendChild(h("div", { class: "picker__empty", text: "No results" }));
        return;
      }
      matched.forEach(function (value) {
        var row = h("button", {
          class: "picker__row" + (isSelected(value) ? " is-selected" : ""),
          type: "button",
          onclick: function () {
            if (!multi) {
              closePicker();
              onPick(value);
              return;
            }
            var index = selected.indexOf(value);
            if (index >= 0) selected.splice(index, 1);
            else selected.push(value);
            onPick(selected.slice());
            paint();
          },
        }, [
          multi ? h("span", { class: "picker__check", text: isSelected(value) ? "✓" : "" }) : null,
          h("code", { class: "picker__value", text: value }),
          info.descriptions && info.descriptions[value]
            ? h("span", { class: "picker__desc", text: info.descriptions[value] })
            : null,
        ]);
        list.appendChild(row);
      });
    }

    if (info.values.length > 8) {
      var search = h("input", { class: "picker__search", type: "text", placeholder: "Search values" });
      search.addEventListener("input", function () {
        filterValue = search.value;
        paint();
      });
      panel.appendChild(search);
      setTimeout(function () { search.focus(); }, 20);
    }

    panel.appendChild(h("div", {
      class: "picker__meta",
      text: multi ? info.values.length + " values · multi select" : info.values.length + " values",
    }));
    panel.appendChild(list);
    paint();

    var rect = anchor.getBoundingClientRect();
    panel.style.left = Math.min(rect.left, window.innerWidth - 320) + "px";
    panel.style.top = rect.bottom + 8 + "px";
    document.body.appendChild(panel);
    requestAnimationFrame(function () { panel.classList.add("is-shown"); });
    activePicker = panel;
    activeAnchor = anchor;
    anchor.classList.add("is-open");
  }

  function applyOverride(target, segments, value) {
    if (!target || typeof target !== "object") return;
    var segment = segments[0];
    if (segment.slice(-2) === "[]") {
      var key = segment.slice(0, -2);
      if (Array.isArray(target[key])) {
        target[key].forEach(function (item) { applyOverride(item, segments.slice(1), value); });
      }
      return;
    }
    if (segments.length === 1) {
      target[segment] = value;
      return;
    }
    applyOverride(target[segment], segments.slice(1), value);
  }

  function sampleWithOverrides(entry, schema) {
    var value = sample(schema);
    var overrides = entry.overrides || {};
    Object.keys(overrides).forEach(function (path) {
      applyOverride(value, path.split("."), overrides[path]);
    });
    return value;
  }

  function schemaProperties(schema) {
    var resolved = merged(schema, []);
    return resolved && resolved.properties ? Object.keys(resolved.properties) : null;
  }

  /* ---------------- annotated json schema view ---------------- */

  function scalarSample(resolved) {
    if (resolved.example !== undefined) return JSON.stringify(resolved.example);
    if (resolved.enum && resolved.enum.length) return JSON.stringify(resolved.enum[0]);
    switch (resolved.type) {
      case "integer":
      case "number": return "0";
      case "boolean": return "true";
      case "string":
        if (resolved.format === "date-time") return '"2026-01-01T09:00:00"';
        if (resolved.format === "date") return '"2026-01-01"';
        if (resolved.format === "uuid") return '"00000000-0000-0000-0000-000000000000"';
        return '"string"';
      default: return "null";
    }
  }

  function jsonLine(depth, parts, extra) {
    return h("div", { class: "json__line" }, [
      h("span", { class: "json__indent", style: "width:" + depth * 14 + "px" }),
      parts,
      extra,
    ]);
  }

  function annotation(resolved, schema, seen) {
    var pieces = [h("span", { class: "json__type", text: kotlinType(schema, seen) })];
    var description = withoutEnumLines(resolved.description || "");
    if (description) pieces.push(h("span", { class: "json__desc", text: description.replace(/\n+/g, " ") }));
    return h("span", { class: "json__meta" }, pieces);
  }

  function emitSchema(container, key, schema, depth, seen, required, last, ctx) {
    var trail = seen || [];
    if (schema && schema.$ref) {
      if (trail.indexOf(schema.$ref) >= 0 || depth > 8) {
        container.appendChild(jsonLine(depth, [
          key ? h("span", { class: "tok-key", text: '"' + key + '"' }) : null,
          key ? h("span", { class: "tok-punc", text: ": " }) : null,
          h("span", { class: "json__cycle", text: "{ … }" }),
        ]));
        return;
      }
      trail = trail.concat([schema.$ref]);
    }

    var resolved = merged(schema, seen || []);
    if (!resolved) return;

    var isArray = resolved.type === "array" && resolved.items;
    var itemSchema = isArray ? resolved.items : schema;
    var itemResolved = isArray ? merged(resolved.items, trail) : resolved;
    var expandable = !!(itemResolved && itemResolved.properties);

    var keyParts = key === null ? [] : [
      h("span", { class: "tok-key", text: '"' + key + '"' }),
      required
        ? h("span", { class: "json__required", title: "required", text: "*" })
        : h("span", { class: "json__optional", title: "optional", text: "?" }),
      h("span", { class: "tok-punc", text: ": " }),
    ];

    if (!expandable) {
      var info = enumInfo(isArray ? resolved.items : schema);
      var valueNode;

      if (info && ctx && ctx.path) {
        var stored = (ctx.entry.overrides || {})[ctx.path];
        var chosen = isArray
          ? (Array.isArray(stored) ? stored : [info.values[0]])
          : (stored || info.values[0]);

        var label = h("span", { class: "tok-str" });
        var token = h("button", { class: "json__pick", type: "button" }, [
          label,
          h("span", { class: "json__pick-caret", text: "▾" }),
        ]);

        var paintToken = function (value) {
          if (isArray) {
            label.textContent = value.length
              ? value.map(function (item) { return '"' + item + '"'; }).join(", ")
              : "";
            token.title = value.length + " selected";
          } else {
            label.textContent = '"' + value + '"';
            token.title = info.descriptions ? info.descriptions[value] || "" : "";
          }
        };
        paintToken(chosen);

        token.addEventListener("click", function (event) {
          event.stopPropagation();
          var currentValue = (ctx.entry.overrides || {})[ctx.path];
          if (isArray && !Array.isArray(currentValue)) currentValue = chosen;
          openPicker(token, info, currentValue || (isArray ? chosen : info.values[0]), function (value) {
            ctx.entry.overrides = ctx.entry.overrides || {};
            ctx.entry.overrides[ctx.path] = value;
            paintToken(value);
            if (ctx.entry.refreshSamples) ctx.entry.refreshSamples();
          }, isArray);
        });

        valueNode = isArray
          ? h("span", null, [h("span", { class: "tok-punc", text: "[" }), token, h("span", { class: "tok-punc", text: "]" })])
          : token;
      } else {
        valueNode = h("span", {
          class: isArray ? "tok-punc" : /^"/.test(scalarSample(itemResolved)) ? "tok-str" : "tok-num",
          text: isArray ? "[" + scalarSample(itemResolved) + "]" : scalarSample(itemResolved),
        });
      }

      container.appendChild(jsonLine(depth, keyParts.concat([
        valueNode,
        last ? null : h("span", { class: "tok-punc", text: "," }),
      ]), annotation(resolved, schema, seen || [])));
      return;
    }

    var fieldCount = Object.keys(itemResolved.properties).length;
    var startOpen = depth < 1;
    var open = isArray ? "[{" : "{";
    var close = (isArray ? "}]" : "}") + (last ? "" : ",");
    var children = h("div", { class: "json__children" + (startOpen ? "" : " is-hidden") });
    var caret = h("button", { class: "json__caret" + (startOpen ? " is-open" : ""), type: "button", text: startOpen ? "▾" : "▸" });
    var collapsed = h("span", {
      class: "json__collapsed" + (startOpen ? "" : " is-shown"),
      text: (isArray ? "[{ … }]" : "{ … }") + " " + fieldCount + " fields",
    });

    var head = jsonLine(depth, keyParts.concat([caret, h("span", { class: "tok-punc", text: open }), collapsed]),
      annotation(resolved, schema, seen || []));
    head.classList.add("json__line--toggle");
    container.appendChild(head);
    container.appendChild(children);

    var tail = jsonLine(depth, [h("span", { class: "tok-punc", text: close })]);
    if (!startOpen) tail.classList.add("is-hidden");
    container.appendChild(tail);

    head.addEventListener("click", function () {
      var isOpen = caret.classList.toggle("is-open");
      caret.textContent = isOpen ? "▾" : "▸";
      children.classList.toggle("is-hidden", !isOpen);
      tail.classList.toggle("is-hidden", !isOpen);
      collapsed.classList.toggle("is-shown", !isOpen);
    });

    var properties = itemResolved.properties;
    var requiredKeys = itemResolved.required || [];
    var names = Object.keys(properties);
    var basePath = ctx && ctx.path ? ctx.path + (isArray ? "[]" : "") + "." : isArray && key ? key + "[]." : "";
    names.forEach(function (name, index) {
      var childCtx = ctx && ctx.entry ? { entry: ctx.entry, path: basePath + name } : null;
      emitSchema(children, name, properties[name], depth + 1, trail, requiredKeys.indexOf(name) >= 0, index === names.length - 1, childCtx);
    });
  }

  function jsonSchemaView(schema, entry) {
    var view = h("div", { class: "json" });
    emitSchema(view, null, schema, 0, [], false, true, entry ? { entry: entry, path: "" } : null);

    var carets = view.querySelectorAll(".json__caret");
    if (carets.length < 2) return view;

    var toggle = h("button", { class: "json__action", type: "button", text: "Expand all" });
    toggle.addEventListener("click", function () {
      var expand = toggle.textContent === "Expand all";
      view.querySelectorAll(".json__line--toggle").forEach(function (line) {
        var caret = line.querySelector(".json__caret");
        if (caret && caret.classList.contains("is-open") !== expand) line.click();
      });
      toggle.textContent = expand ? "Collapse all" : "Expand all";
    });

    return h("div", { class: "json-wrap" }, [h("div", { class: "json__toolbar" }, toggle), view]);
  }

  function legend() {
    return h("span", { class: "legend" }, [
      h("span", { class: "params__required", text: "*" }),
      h("span", { text: "required" }),
      h("span", { class: "params__optional", text: "?" }),
      h("span", { text: "optional" }),
    ]);
  }

  function block(title, body, extra) {
    if (!body) return null;
    return h("section", { class: "block" }, [
      h("h3", { class: "block__title" }, [title, extra, legend()]),
      body,
    ]);
  }

  /* ---------------- code panel ---------------- */

  function multipartFields(schema) {
    var resolved = merged(schema, []);
    if (!resolved || !resolved.properties) return [];
    return Object.keys(resolved.properties).map(function (name) {
      return { name: name, schema: resolved.properties[name], binary: isBinary(resolved.properties[name]) };
    });
  }

  function cookieHeader(request) {
    var names = Object.keys(request.cookies || {});
    if (!names.length) return null;
    return names.map(function (name) { return name + "=" + request.cookies[name]; }).join("; ");
  }

  function curlOf(entry, request) {
    var parts = ["curl -X " + entry.method.toUpperCase() + " '" + request.url + "'"];
    Object.keys(request.headers).forEach(function (key) {
      parts.push("-H '" + key + ": " + request.headers[key] + "'");
    });
    var cookie = cookieHeader(request);
    if (cookie) parts.push("-b '" + cookie + "'");

    if (request.multipart) {
      multipartFields(request.schema).forEach(function (field) {
        parts.push(field.binary ? "-F '" + field.name + "=@/path/to/file'" : "-F '" + field.name + "=value'");
      });
    } else if (request.body) {
      parts.push("-d '" + request.body.replace(/\n\s*/g, "") + "'");
    }
    return parts.join(" \\\n  ");
  }

  function bodyPayloadOf(entry, body) {
    if (!body) return null;
    if (body.examples && entry.exampleKey && body.examples[entry.exampleKey]) {
      var picked = body.examples[entry.exampleKey];
      var value = picked.value !== undefined ? picked.value : picked;
      return typeof value === "string" ? value : JSON.stringify(value, null, 2);
    }
    if (body.example !== undefined) {
      return typeof body.example === "string" ? body.example : JSON.stringify(body.example, null, 2);
    }
    return JSON.stringify(sampleWithOverrides(entry, body.schema), null, 2);
  }

  function staticRequest(entry) {
    var body = requestBodyOf(entry.operation, entry.mediaType);
    var auth = authContributions();
    var headers = {};

    if (body && !isMultipart(body.mediaType)) headers["Content-Type"] = body.mediaType;
    Object.assign(headers, customHeaders(), auth.headers);

    var query = Object.keys(auth.query).map(function (name) {
      return encodeURIComponent(name) + "=" + encodeURIComponent(auth.query[name]);
    });

    return {
      url: (state.server || "").replace(/\/$/, "") + entry.path + (query.length ? "?" + query.join("&") : ""),
      headers: headers,
      cookies: Object.assign({}, customCookies(), auth.cookies),
      multipart: body ? isMultipart(body.mediaType) : false,
      schema: body ? body.schema : null,
      body: body ? bodyPayloadOf(entry, body) : null,
    };
  }

  function kotlinSnippet(entry, request) {
    var lines = ['val client = RestClient.create("' + (state.server || "").replace(/\/$/, "") + '")', "", "val response = client"];
    lines.push("    ." + entry.method + "()");
    lines.push('    .uri("' + entry.path + '")');
    if (request.headers["Content-Type"]) lines.push("    .contentType(MediaType.APPLICATION_JSON)");
    Object.keys(request.headers).forEach(function (key) {
      if (key !== "Content-Type") lines.push('    .header("' + key + '", "' + request.headers[key] + '")');
    });
    var cookie = cookieHeader(request);
    if (cookie) lines.push('    .header("Cookie", "' + cookie + '")');
    if (request.body) {
      lines.push("    .body(");
      lines.push('        """');
      request.body.split("\n").forEach(function (line) { lines.push("        " + line); });
      lines.push('        """.trimIndent(),');
      lines.push("    )");
    }
    lines.push("    .retrieve()");
    lines.push("    .body(String::class.java)");
    return lines.join("\n");
  }

  function javascriptSnippet(entry, request) {
    var lines = [];
    var options = ['  method: "' + entry.method.toUpperCase() + '"'];
    var headerKeys = Object.keys(request.headers);
    if (headerKeys.length) {
      options.push("  headers: {\n" + headerKeys.map(function (key) {
        return '    "' + key + '": "' + request.headers[key] + '"';
      }).join(",\n") + "\n  }");
    }
    options.push('  credentials: "include"');

    if (request.multipart) {
      lines.push("const form = new FormData();");
      multipartFields(request.schema).forEach(function (field) {
        lines.push(field.binary
          ? 'form.append("' + field.name + '", fileInput.files[0]);'
          : 'form.append("' + field.name + '", "value");');
      });
      lines.push("");
      options.push("  body: form");
    } else if (request.body) {
      options.push("  body: JSON.stringify(" + request.body.replace(/\n/g, "\n  ") + ")");
    }

    lines.push('const response = await fetch("' + request.url + '", {');
    lines.push(options.join(",\n"));
    lines.push("});");
    lines.push("");
    lines.push("const data = await response.json();");
    return lines.join("\n");
  }

  function tsType(schema, seen) {
    var trail = seen || [];
    var resolved = merged(schema, trail);
    if (!resolved) return "unknown";
    var name = refName(schema);
    if (schema && schema.$ref) trail = trail.concat([schema.$ref]);

    if (resolved.enum) return resolved.enum.map(function (value) { return JSON.stringify(value); }).join(" | ");
    if (resolved.type === "array") return tsType(resolved.items, trail) + "[]";
    if (resolved.properties || resolved.type === "object") {
      if (name) return name;
      if (resolved.additionalProperties) return "Record<string, " + tsType(resolved.additionalProperties, trail) + ">";
      return "Record<string, unknown>";
    }
    if (resolved.type === "integer" || resolved.type === "number") return "number";
    if (resolved.type === "boolean") return "boolean";
    return "string";
  }

  function collectInterfaces(schema, out, seen) {
    var trail = seen || [];
    if (schema && schema.$ref) {
      if (trail.indexOf(schema.$ref) >= 0) return out;
      trail = trail.concat([schema.$ref]);
    }
    var resolved = merged(schema, seen || []);
    if (!resolved) return out;

    if (resolved.type === "array" && resolved.items) return collectInterfaces(resolved.items, out, trail);
    if (!resolved.properties) return out;

    var name = refName(schema);
    if (name && !out.some(function (item) { return item.name === name; })) {
      var required = resolved.required || [];
      var fields = Object.keys(resolved.properties).map(function (key) {
        var optional = required.indexOf(key) >= 0 ? "" : "?";
        return "  " + key + optional + ": " + tsType(resolved.properties[key], trail) + ";";
      });
      out.push({ name: name, body: "interface " + name + " {\n" + fields.join("\n") + "\n}" });
      Object.keys(resolved.properties).forEach(function (key) {
        collectInterfaces(resolved.properties[key], out, trail);
      });
    }
    return out;
  }

  function typescriptSnippet(entry, request) {
    var body = requestBodyOf(entry.operation);
    var success = Object.keys(entry.operation.responses || {}).filter(function (code) { return /^2/.test(code); })[0];
    var successContent = success ? contentOf(resolve(entry.operation.responses[success], [])) : null;

    var interfaces = [];
    if (body) collectInterfaces(body.schema, interfaces, []);
    if (successContent && successContent.schema) collectInterfaces(successContent.schema, interfaces, []);

    var lines = interfaces.map(function (item) { return item.body; });
    if (lines.length) lines.push("");

    var options = ['  method: "' + entry.method.toUpperCase() + '"'];
    var headerKeys = Object.keys(request.headers);
    if (headerKeys.length) {
      options.push("  headers: {\n" + headerKeys.map(function (key) {
        return '    "' + key + '": "' + request.headers[key] + '"';
      }).join(",\n") + "\n  }");
    }
    options.push('  credentials: "include"');
    if (request.body) {
      var typed = body ? " satisfies " + tsType(body.schema, []) : "";
      options.push("  body: JSON.stringify(" + request.body.replace(/\n/g, "\n  ") + typed + ")");
    }
    void success;

    lines.push('const response = await fetch("' + request.url + '", {');
    lines.push(options.join(",\n"));
    lines.push("});");

    if (successContent && successContent.schema) {
      lines.push("");
      lines.push("const data: " + tsType(successContent.schema, []) + " = await response.json();");
    }
    return lines.join("\n");
  }

  function codePanel(entry) {
    var responses = entry.operation.responses || {};
    var codes = Object.keys(responses);

    var samples = [
      { label: "cURL", build: function () { return curlOf(entry, staticRequest(entry)); } },
      { label: "Kotlin", build: function () { return kotlinSnippet(entry, staticRequest(entry)); } },
      { label: "TypeScript", build: function () { return typescriptSnippet(entry, staticRequest(entry)); } },
      { label: "JavaScript", build: function () { return javascriptSnippet(entry, staticRequest(entry)); } },
    ];

    var active = samples[0];
    var requestView = tabs(samples.map(function (item) {
      return {
        label: item.label,
        render: function () {
          active = item;
          return h("pre", { class: "code", text: item.build() });
        },
      };
    }));

    var requestCard = h("div", { class: "panel" }, [
      h("div", { class: "panel__head" }, [
        requestView.strip,
        copyButton(function () { return active.build(); }),
      ]),
      requestView.body,
    ]);

    entry.refreshSamples = function () {
      var current = requestView.strip.querySelector(".is-active");
      var index = Array.prototype.indexOf.call(requestView.strip.children, current);
      requestView.activate(index < 0 ? 0 : index);
    };

    var responseTabs = codes.map(function (code) {
      return {
        label: code,
        code: code,
        render: function () {
          var response = resolve(responses[code], []) || {};
          var content = contentOf(response);
          return h("div", null, [
            response.description ? h("div", { class: "panel__note", text: response.description }) : null,
            content && content.schema
              ? h("pre", { class: "code", html: highlightJson(sample(content.schema)) })
              : h("div", { class: "panel__empty", text: "No body" }),
          ]);
        },
      };
    });

    var responseView = tabs(responseTabs);
    responseView.strip.classList.add("tabs--status");
    Array.prototype.forEach.call(responseView.strip.children, function (button, index) {
      button.classList.add("tabs__tab--status", "is-" + String(codes[index]).charAt(0));
    });

    var responseCard = h("div", { class: "panel" }, [
      h("div", { class: "panel__head" }, [
        h("span", { class: "panel__label", text: "Response" }),
        responseView.strip,
      ]),
      responseView.body,
    ]);

    var stack = h("div", { class: "aside__stack" }, [requestCard, responseCard]);

    if (state.config.tryItOut) {
      var tryCard = tryPanel(entry);
      var toggle = h("button", { class: "btn btn--primary btn--block", type: "button", text: "Try it out" });
      toggle.addEventListener("click", function () {
        var showing = tryCard.classList.toggle("is-shown");
        toggle.textContent = showing ? "Close" : "Try it out";
        if (showing) tryCard.scrollIntoView({ behavior: "smooth", block: "nearest" });
      });
      stack.insertBefore(toggle, requestCard);
      stack.appendChild(tryCard);
    }

    return h("aside", { class: "op__aside" }, stack);
  }

  /* ---------------- try it out ---------------- */

  function tryPanel(entry) {
    var params = parametersOf(entry);
    var body = requestBodyOf(entry.operation, entry.mediaType);
    var multipart = body ? isMultipart(body.mediaType) : false;
    var formEncoded = body ? isFormUrlEncoded(body.mediaType) : false;
    var inputs = [];
    var partInputs = [];
    var fields = h("div", { class: "try__fields" });

    params.forEach(function (param) {
      var info = enumInfo(param.schema);
      var control;
      var getter;
      if (info) {
        var box = select(info.values.map(function (value) {
          return { value: value, label: value, description: info.descriptions ? info.descriptions[value] : null };
        }), info.values[0]);
        control = box.node;
        getter = box.get;
      } else {
        var input = h("input", { class: "input", type: "text", placeholder: kotlinType(param.schema) });
        control = input;
        getter = function () { return input.value; };
      }
      inputs.push({ param: param, get: getter });
      fields.appendChild(h("label", { class: "try__field" }, [
        h("span", { class: "try__label" }, [
          param.name,
          param.required ? h("span", { class: "params__required", text: "*" }) : null,
          h("span", { class: "try__in", text: param.in }),
        ]),
        control,
      ]));
    });

    var bodyArea = null;
    if (body && (multipart || formEncoded)) {
      multipartFields(body.schema).forEach(function (field) {
        var input = field.binary
          ? h("input", { class: "input input--file", type: "file" })
          : h("input", { class: "input", type: "text", placeholder: kotlinType(field.schema) });
        partInputs.push({ field: field, input: input });
        fields.appendChild(h("label", { class: "try__field" }, [
          h("span", { class: "try__label" }, [
            field.name,
            h("span", { class: "try__in", text: field.binary ? "file" : "form" }),
          ]),
          input,
        ]));
      });
    } else if (body) {
      bodyArea = h("textarea", { class: "textarea", spellcheck: "false" });
      bodyArea.value = bodyPayloadOf(entry, body) || "";
      fields.appendChild(h("label", { class: "try__field" }, [
        h("span", { class: "try__label", text: "Body · " + body.mediaType }),
        bodyArea,
      ]));
    }

    function buildRequest() {
      var url = entry.path;
      var query = [];
      var headers = {};
      var auth = authContributions();

      inputs.forEach(function (item) {
        var value = item.get();
        if (value === "" || value === null || value === undefined) return;
        if (item.param.in === "path") url = url.replace("{" + item.param.name + "}", encodeURIComponent(value));
        else if (item.param.in === "query") query.push(encodeURIComponent(item.param.name) + "=" + encodeURIComponent(value));
        else if (item.param.in === "header") headers[item.param.name] = value;
      });

      Object.keys(auth.query).forEach(function (name) {
        query.push(encodeURIComponent(name) + "=" + encodeURIComponent(auth.query[name]));
      });
      Object.assign(headers, customHeaders(), auth.headers);
      if (body && !multipart) headers["Content-Type"] = body.mediaType;

      var payload = null;
      if (multipart || formEncoded) {
        if (multipart) {
          payload = new FormData();
          partInputs.forEach(function (part) {
            if (part.field.binary) {
              if (part.input.files && part.input.files[0]) payload.append(part.field.name, part.input.files[0]);
            } else if (part.input.value) {
              payload.append(part.field.name, part.input.value);
            }
          });
        } else {
          payload = partInputs
            .filter(function (part) { return part.input.value; })
            .map(function (part) { return encodeURIComponent(part.field.name) + "=" + encodeURIComponent(part.input.value); })
            .join("&");
        }
      } else if (bodyArea) {
        payload = bodyArea.value;
      }

      return {
        url: (state.server || "").replace(/\/$/, "") + url + (query.length ? "?" + query.join("&") : ""),
        headers: headers,
        cookies: Object.assign({}, customCookies(), auth.cookies),
        multipart: multipart,
        schema: body ? body.schema : null,
        body: payload,
      };
    }

    var meta = h("span", { class: "try__meta" });
    var result = h("div", { class: "try__result" });
    var execute = h("button", { class: "btn btn--primary", type: "button", text: "Send" });

    execute.addEventListener("click", function () {
      var request = buildRequest();
      applyCookies(request.cookies);
      execute.disabled = true;
      meta.textContent = "Sending…";
      var started = performance.now();
      fetch(request.url, {
        method: entry.method.toUpperCase(),
        headers: request.headers,
        body: ["get", "head"].indexOf(entry.method) >= 0 ? undefined : request.body,
        credentials: "include",
      }).then(function (response) {
        return response.text().then(function (text) { return { response: response, text: text }; });
      }).then(function (payload) {
        meta.textContent = Math.round(performance.now() - started) + "ms";
        var parsed;
        try {
          parsed = payload.text ? JSON.parse(payload.text) : null;
        } catch (error) {
          parsed = payload.text;
        }
        clear(result);
        result.appendChild(h("div", { class: "try__status" }, [
          h("span", {
            class: "status status--" + String(payload.response.status).charAt(0),
            text: payload.response.status + " " + payload.response.statusText,
          }),
          h("span", { class: "try__url", text: request.url }),
        ]));
        result.appendChild(parsed === null || typeof parsed === "string"
          ? h("pre", { class: "code code--wrap", text: parsed || "(empty)" })
          : h("pre", { class: "code", html: highlightJson(parsed) }));
      }).catch(function (error) {
        meta.textContent = "";
        clear(result);
        result.appendChild(h("div", { class: "try__error", text: "Request failed: " + error.message }));
      }).finally(function () {
        execute.disabled = false;
      });
    });

    var curlCopy = h("button", { class: "btn btn--sm", type: "button", text: "Copy cURL" });
    curlCopy.addEventListener("click", function () { copy(curlOf(entry, buildRequest()), curlCopy); });

    return h("div", { class: "panel try" }, [
      h("div", { class: "panel__head" }, h("span", { class: "panel__label", text: "Try it out" })),
      h("div", { class: "try__inner" }, [
        fields,
        h("div", { class: "try__actions" }, [execute, curlCopy, meta]),
        result,
      ]),
    ]);
  }

  /* ---------------- operation ---------------- */

  function operationMarkdown(entry) {
    var op = entry.operation;
    var lines = ["## " + entry.method.toUpperCase() + " " + entry.path];
    if (op.summary) lines.push("", op.summary);
    if (op.description) lines.push("", op.description);

    var params = parametersOf(entry);
    if (params.length) {
      lines.push("", "### Parameters", "", "| Type | Name | Category | Description |", "| --- | --- | --- | --- |");
      params.forEach(function (param) {
        lines.push("| " + kotlinType(param.schema) + " | " + param.name + (param.required ? " *" : "") +
          " | " + param.in + " | " + String(descriptionOf(param) || "").replace(/\n+/g, " ") + " |");
      });
    }

    var body = requestBodyOf(op);
    if (body) lines.push("", "### Request body", "", "```json", JSON.stringify(sample(body.schema), null, 2), "```");

    lines.push("", "### Responses");
    Object.keys(op.responses || {}).forEach(function (code) {
      var response = resolve(op.responses[code], []) || {};
      lines.push("", "**" + code + "** " + (response.description || ""));
      var content = contentOf(response);
      if (content && content.schema) {
        lines.push("", "```json", JSON.stringify(sample(content.schema), null, 2), "```");
      }
    });
    return lines.join("\n");
  }

  function policyCard(description) {
    var content = enhanceMarkdown(h("div", { class: "policy__content md", html: markdown(description) }));
    var card = h("section", { class: "policy" }, [
      h("div", { class: "policy__head" }, [
        h("span", { class: "policy__badge", text: "Policy" }),
        h("span", { class: "policy__hint", text: "Rules and caveats for this endpoint" }),
      ]),
      content,
    ]);

    requestAnimationFrame(function () {
      if (content.scrollHeight <= 320) return;
      content.classList.add("is-clamped");
      content.style.maxHeight = "220px";
      var toggle = h("button", {
        class: "policy__toggle",
        type: "button",
        text: "Show more",
        onclick: function () {
          var expanded = content.classList.toggle("is-clamped") === false;
          content.style.maxHeight = expanded ? content.scrollHeight + "px" : "220px";
          toggle.textContent = expanded ? "Show less" : "Show more";
        },
      });
      card.appendChild(toggle);
    });

    return card;
  }

  function requestBodyBlock(entry, body) {
    var controls = [h("span", { class: "chip", text: body.mediaType })];

    if (body.mediaTypes && body.mediaTypes.length > 1) {
      controls.push(select(body.mediaTypes.map(function (type) {
        return { value: type, label: type };
      }), body.mediaType, function (value) {
        entry.mediaType = value;
        rerenderOperation(entry);
      }).node);
    }

    if (body.examples) {
      var names = Object.keys(body.examples);
      controls.push(select(names.map(function (name) {
        return { value: name, label: name, description: body.examples[name].summary };
      }), entry.exampleKey || names[0], function (value) {
        entry.exampleKey = value;
        rerenderOperation(entry);
      }).node);
    }

    var view = isMultipart(body.mediaType) || isFormUrlEncoded(body.mediaType)
      ? formFieldTable(body.schema)
      : jsonSchemaView(body.schema, entry);

    return block("Request body", view, h("span", { class: "block__controls" }, controls));
  }

  function formFieldTable(schema) {
    var fields = multipartFields(schema).map(function (field) {
      var target = merged(field.schema, []) || {};
      return {
        name: field.name,
        in: field.binary ? "file" : "form",
        required: false,
        description: target.description,
        schema: field.schema,
      };
    });
    return fields.length ? parameterTable(fields) : null;
  }

  function rerenderOperation(entry) {
    var current = document.getElementById(operationId(entry));
    if (!current) return;
    var replacement = operationSection(entry);
    current.replaceWith(replacement);
  }

  function operationSection(entry) {
    var op = entry.operation;
    var id = operationId(entry);
    var params = parametersOf(entry);
    var body = requestBodyOf(op);
    var bodyProperties = body ? schemaProperties(body.schema) : null;
    var id = operationId(entry);

    var prose = h("div", { class: "op__prose" }, [
      h("div", { class: "op__head" }, [
        h("span", { class: "method method--lg method--" + entry.method, text: entry.method.toUpperCase() }),
        h("code", { class: "op__path", text: entry.path }),
        h("a", { class: "op__anchor", href: "#" + id, title: "Link to this endpoint", text: "#" }),
        op.deprecated ? h("span", { class: "op__deprecated", text: "deprecated" }) : null,
        requirementsOf(entry).length
          ? h("span", { class: "op__lock", title: "Requires auth: " + requirementsOf(entry).join(", "), text: "🔒" })
          : null,
        h("span", { class: "op__spacer" }),
        copyButton(function () { return operationMarkdown(entry); }, "Copy policy"),
      ]),
      op.summary ? h("h2", { class: "op__title", text: op.summary }) : null,
      op.description ? policyCard(op.description) : null,
      params.length ? block("Parameters", parameterTable(params)) : null,
      body ? requestBodyBlock(entry, body) : null,
      block("Responses", responseTable(entry)),
    ]);

    return h("section", { class: "op op--" + entry.method, id: id }, [prose, codePanel(entry)]);
  }

  function headerTable(headers) {
    var names = Object.keys(headers || {});
    if (!names.length) return null;
    var grid = h("div", { class: "params params--headers" }, [
      h("div", { class: "params__head", text: "Type" }),
      h("div", { class: "params__head", text: "Header" }),
      h("div", { class: "params__head", text: "Description" }),
    ]);
    names.forEach(function (name) {
      var header = resolve(headers[name], []) || {};
      grid.appendChild(h("div", { class: "params__cell params__type", text: kotlinType(header.schema) }));
      grid.appendChild(h("div", { class: "params__cell params__name", text: name }));
      grid.appendChild(h("div", { class: "params__cell params__description", text: header.description || "" }));
    });
    return h("div", { class: "responses__headers" }, [
      h("div", { class: "responses__sub", text: "Response headers" }),
      grid,
    ]);
  }

  function responseTable(entry) {
    var responses = entry.operation.responses || {};
    var rows = h("div", { class: "responses" });
    Object.keys(responses).forEach(function (code) {
      var response = resolve(responses[code], []) || {};
      var content = contentOf(response);
      var properties = content ? schemaProperties(content.schema) : null;
      rows.appendChild(h("div", { class: "responses__row" }, [
        h("div", { class: "responses__head" }, [
          h("span", { class: "status status--" + (/^\d/.test(code) ? code.charAt(0) : "default"), text: code }),
          h("span", { class: "responses__desc", text: response.description || "" }),
          content ? h("span", { class: "responses__type", text: kotlinType(content.schema) }) : null,
        ]),
        headerTable(response.headers),
        properties && properties.length ? jsonSchemaView(content.schema) : null,
      ]));
    });
    return rows;
  }

  /* ---------------- page ---------------- */

  function collectEntries() {
    var entries = [];
    var paths = state.spec.paths || {};
    Object.keys(paths).forEach(function (path) {
      var pathItem = paths[path];
      METHODS.forEach(function (method) {
        if (!pathItem[method]) return;
        entries.push({
          path: path,
          method: method,
          operation: pathItem[method],
          pathItem: pathItem,
          tag: (pathItem[method].tags || ["default"])[0],
        });
      });
    });
    return entries;
  }

  function matchesFilter(entry) {
    if (!state.filter) return true;
    var needle = state.filter.toLowerCase();
    return (entry.path + " " + entry.method + " " + (entry.operation.summary || "") + " " + entry.tag)
      .toLowerCase().indexOf(needle) >= 0;
  }

  function groupedEntries() {
    var groups = [];
    var index = {};
    state.entries.filter(matchesFilter).forEach(function (entry) {
      if (!index[entry.tag]) {
        index[entry.tag] = { tag: entry.tag, entries: [] };
        groups.push(index[entry.tag]);
      }
      index[entry.tag].entries.push(entry);
    });
    return groups;
  }

  function tagDescription(name) {
    var found = (state.spec.tags || []).filter(function (tag) { return tag.name === name; })[0];
    return found ? found.description : null;
  }

  function renderSidebar() {
    var sidebar = clear(document.getElementById("sidebar"));
    var info = state.spec.info || {};

    sidebar.appendChild(h("a", { class: "brand", href: "#top" }, [
      h("span", { class: "brand__mark" }),
      h("span", null, [
        h("span", { class: "brand__name", text: state.config.title || info.title || "API" }),
        info.version ? h("span", { class: "brand__version", text: "v" + info.version }) : null,
      ]),
    ]));

    var search = h("input", { class: "search", type: "search", placeholder: "Search", value: state.filter });
    search.addEventListener("input", function () {
      state.filter = search.value;
      renderMain();
      renderNav();
    });
    var jump = h("button", { class: "jump", type: "button" }, [
      h("span", { text: "Quick jump" }),
      h("kbd", { class: "kbd", text: "⌘K" }),
    ]);
    jump.addEventListener("click", function () {
      if (!document.querySelector(".palette")) commandPalette();
    });
    sidebar.appendChild(h("div", { class: "sidebar__tools" }, [search, jump]));
    sidebar.appendChild(h("nav", { class: "nav", id: "nav" }));
    renderNav();
    sidebar.appendChild(exportPanel());

    document.addEventListener("keydown", function (event) {
      var typing = /^(INPUT|TEXTAREA)$/.test(document.activeElement.tagName);
      if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === "k") {
        event.preventDefault();
        if (!document.querySelector(".palette")) commandPalette();
        return;
      }
      if (event.key === "/" && !typing) {
        event.preventDefault();
        search.focus();
      }
    });
  }

  function renderNav() {
    var nav = clear(document.getElementById("nav"));
    groupedEntries().forEach(function (group) {
      var list = h("div", { class: "nav__items" });
      var toggle = h("button", { class: "nav__group-title", type: "button" }, [
        h("span", { class: "nav__caret", text: "▾" }),
        h("span", { text: group.tag }),
        h("span", { class: "nav__count", text: group.entries.length }),
      ]);
      var expanded = !!state.filter || state.openGroups[group.tag];
      var block = h("div", { class: "nav__group" + (expanded ? "" : " is-collapsed") }, [
        toggle,
        h("div", { class: "nav__list" }, list),
      ]);

      toggle.addEventListener("click", function () {
        var nowOpen = !block.classList.toggle("is-collapsed");
        state.openGroups[group.tag] = nowOpen;
      });

      group.entries.forEach(function (entry) {
        var id = operationId(entry);
        list.appendChild(h("a", {
          class: "nav__item",
          href: "#" + id,
          dataset: { target: id },
        }, [
          h("span", { class: "method method--" + entry.method, text: entry.method.toUpperCase() }),
          h("span", { class: "nav__path", text: entry.path }),
        ]));
      });
      nav.appendChild(block);
    });
  }

  /* ---------------- command palette ---------------- */

  function commandPalette() {
    var input = h("input", { class: "palette__input", type: "text", placeholder: "Search endpoints…" });
    var list = h("div", { class: "palette__list" });
    var panel = h("div", { class: "palette__panel" }, [
      h("div", { class: "palette__head" }, [input, h("kbd", { class: "kbd", text: "esc" })]),
      list,
    ]);
    var overlay = h("div", { class: "palette" }, panel);
    var results = [];
    var cursor = 0;

    function close() {
      overlay.classList.remove("is-open");
      setTimeout(function () { overlay.remove(); }, 160);
    }

    function move(delta) {
      if (!results.length) return;
      cursor = (cursor + delta + results.length) % results.length;
      Array.prototype.forEach.call(list.children, function (row, index) {
        row.classList.toggle("is-active", index === cursor);
        if (index === cursor) row.scrollIntoView({ block: "nearest" });
      });
    }

    function go(entry) {
      close();
      location.hash = operationId(entry);
      var target = document.getElementById(operationId(entry));
      if (target) target.scrollIntoView({ behavior: "smooth", block: "start" });
    }

    function refresh() {
      var needle = input.value.toLowerCase();
      results = state.entries.filter(function (entry) {
        return (entry.path + " " + entry.method + " " + (entry.operation.summary || "") + " " + entry.tag)
          .toLowerCase().indexOf(needle) >= 0;
      }).slice(0, 40);
      cursor = 0;
      clear(list);
      results.forEach(function (entry, index) {
        var row = h("button", { class: "palette__row" + (index === 0 ? " is-active" : ""), type: "button" }, [
          h("span", { class: "method method--" + entry.method, text: entry.method.toUpperCase() }),
          h("span", { class: "palette__path", text: entry.path }),
          entry.operation.summary ? h("span", { class: "palette__summary", text: entry.operation.summary }) : null,
          h("span", { class: "palette__tag", text: entry.tag }),
        ]);
        row.addEventListener("click", function () { go(entry); });
        list.appendChild(row);
      });
      if (!results.length) list.appendChild(h("div", { class: "palette__empty", text: "No results" }));
    }

    input.addEventListener("input", refresh);
    overlay.addEventListener("click", function (event) { if (event.target === overlay) close(); });
    input.addEventListener("keydown", function (event) {
      if (event.key === "Escape") close();
      else if (event.key === "ArrowDown") { event.preventDefault(); move(1); }
      else if (event.key === "ArrowUp") { event.preventDefault(); move(-1); }
      else if (event.key === "Enter" && results[cursor]) { event.preventDefault(); go(results[cursor]); }
    });

    document.body.appendChild(overlay);
    requestAnimationFrame(function () { overlay.classList.add("is-open"); });
    refresh();
    input.focus();
  }

  function renderMain() {
    var main = clear(document.getElementById("main"));
    var info = state.spec.info || {};

    var hero = h("header", { class: "hero", id: "top" }, [
      h("h1", { class: "hero__title" }, [
        state.config.title || info.title || "API",
        info.version ? h("span", { class: "chip", text: "v" + info.version }) : null,
        h("span", { class: "chip chip--accent", text: state.spec.openapi ? "OAS " + state.spec.openapi : "OpenAPI" }),
      ]),
      info.description ? enhanceMarkdown(h("div", { class: "hero__description md", html: markdown(info.description) })) : null,
      renderToolbar(),
    ]);
    main.appendChild(hero);

    var groups = groupedEntries();
    if (!groups.length) {
      main.appendChild(h("div", { class: "boot", text: "No endpoints match your search." }));
      return;
    }

    groups.forEach(function (group) {
      main.appendChild(h("div", { class: "tag" }, [
        h("h2", { class: "tag__title", text: group.tag }),
        tagDescription(group.tag) ? h("p", { class: "tag__description", text: tagDescription(group.tag) }) : null,
      ]));
      group.entries.forEach(function (entry) {
        main.appendChild(operationSection(entry));
      });
    });

    observeSections();
  }

  function renderToolbar() {
    var toolbar = h("div", { class: "toolbar" });

    var servers = (state.spec.servers || []).map(function (server) {
      return { value: server.url, label: server.url, description: server.description };
    });
    if (servers.length) {
      state.server = state.server || servers[0].value;
      toolbar.appendChild(h("div", { class: "field" }, [
        h("span", { class: "field__label", text: "Server" }),
        select(servers, state.server, function (value) { state.server = value; }).node,
      ]));
    }

    var schemeNames = manualSchemeNames();
    if (schemeNames.length) {
      var authButton = h("button", { class: "btn", type: "button" });
      var syncAuth = function () {
        var filled = schemeNames.filter(function (name) { return state.auth[name]; }).length;
        authButton.textContent = filled ? "Auth " + filled + "/" + schemeNames.length : "Auth";
        authButton.classList.toggle("btn--active", filled > 0);
      };
      authButton.addEventListener("click", function () { openAuthPanel(syncAuth); });
      syncAuth();
      toolbar.appendChild(h("div", { class: "field" }, [
        h("span", { class: "field__label", text: "Security" }),
        authButton,
      ]));
    }

    var headerButton = h("button", { class: "btn", type: "button" });
    var syncHeaders = function () {
      var count = (state.headers || []).filter(function (row) { return row.enabled !== false && row.name; }).length;
      headerButton.textContent = count ? "Headers " + count : "Headers";
      headerButton.classList.toggle("btn--active", count > 0);
    };
    headerButton.addEventListener("click", function () { openHeaderPanel(syncHeaders); });
    syncHeaders();

    var cookieButton = h("button", { class: "btn", type: "button" });
    var syncCookies = function () {
      var count = (state.cookies || []).filter(function (row) { return row.enabled !== false && row.name; }).length;
      cookieButton.textContent = count ? "Cookies " + count : "Cookies";
      cookieButton.classList.toggle("btn--active", count > 0);
    };
    cookieButton.addEventListener("click", function () { openCookiePanel(syncCookies); });
    syncCookies();

    toolbar.appendChild(h("div", { class: "field" }, [
      h("span", { class: "field__label", text: "Headers" }),
      h("div", { class: "field__row" }, [headerButton, cookieButton]),
    ]));

    return toolbar;
  }

  function exportPanel() {
    var base = location.pathname.replace(/\/index\.html$/, "").replace(/\/$/, "");

    var specRow = exportRow("{ }", "OpenAPI JSON", "The raw spec", state.config.docsUrl, null);
    var typesRow = exportRow("TS", "types.d.ts", "Interfaces and enums", base + "/export/types.d.ts", "types.d.ts");
    var clientRow = exportRow("TS", "client.ts", "Typed fetch client", base + "/export/client.ts", "client.ts");

    var mcpRow = h("button", { class: "export__row", type: "button" }, [
      h("span", { class: "export__icon", text: "AI" }),
      h("span", { class: "export__body" }, [
        h("span", { class: "export__name", text: "MCP server" }),
        h("span", { class: "export__hint", text: "Connect an agent" }),
      ]),
    ]);
    mcpRow.addEventListener("click", function () { openMcpDialog(base); });

    fetch(base + "/export/manifest.json")
      .then(function (response) { return response.ok ? response.json() : null; })
      .then(function (manifest) {
        if (!manifest) return;
        renameExport(typesRow, manifest.types);
        renameExport(clientRow, manifest.client);
      })
      .catch(function () { /* export disabled */ });

    return h("div", { class: "sidebar__footer" }, [
      h("div", { class: "nav__group-title", text: "Export" }),
      h("div", { class: "export" }, [specRow, typesRow, clientRow, mcpRow]),
    ]);
  }

  function exportRow(icon, name, hint, href, download) {
    return h("a", {
      class: "export__row",
      href: href,
      download: download,
      target: download ? null : "_blank",
    }, [
      h("span", { class: "export__icon", text: icon }),
      h("span", { class: "export__body" }, [
        h("span", { class: "export__name", text: name }),
        h("span", { class: "export__hint", text: hint }),
      ]),
      download ? h("span", { class: "export__action", text: "↓" }) : null,
    ]);
  }

  function renameExport(row, filename) {
    row.download = filename;
    row.querySelector(".export__name").textContent = filename;
  }

  function openMcpDialog(base) {
    var url = location.origin + base + "/mcp";
    var servers = {};
    servers[state.config.mcpName || "openapi-docs"] = { type: "http", url: url };
    var config = JSON.stringify({ mcpServers: servers }, null, 2);
    var body = h("div", { class: "modal__body" }, [
      h("p", { class: "modal__note", text: "This documentation is also served as an MCP server, so an AI agent can read the endpoints, schemas and policies directly." }),
      h("pre", { class: "code code--wrap", text: config }),
      h("p", { class: "modal__note", text: "Tools: list_endpoints, get_endpoint, search_docs, get_schema, get_typescript." }),
    ]);
    var copyConfig = h("button", { class: "btn btn--sm", type: "button", text: "Copy config" });
    copyConfig.addEventListener("click", function () { copy(config, copyConfig); });
    body.appendChild(copyConfig);
    modal("MCP server", body);
  }

  function modal(title, body, onClose) {
    var panel = h("div", { class: "modal__panel" }, [
      h("div", { class: "modal__head" }, [
        h("h3", { class: "modal__title", text: title }),
        h("button", { class: "btn btn--sm", type: "button", text: "Close", onclick: function () { close(); } }),
      ]),
      body,
    ]);
    var overlay = h("div", { class: "modal" }, panel);

    function close() {
      overlay.classList.remove("is-open");
      setTimeout(function () { overlay.remove(); }, 160);
      if (onClose) onClose();
    }

    overlay.addEventListener("click", function (event) { if (event.target === overlay) close(); });
    document.body.appendChild(overlay);
    requestAnimationFrame(function () { overlay.classList.add("is-open"); });
    return close;
  }

  function openAuthPanel(onChange) {
    var schemes = securitySchemes();
    var body = h("div", { class: "modal__body" });

    manualSchemeNames().forEach(function (name) {
      var scheme = schemes[name];
      var hint = scheme.type === "apiKey"
        ? scheme.type + " · " + scheme["in"] + " · " + scheme.name
        : scheme.type + (scheme.scheme ? " · " + scheme.scheme : "");

      var input = h("input", {
        class: "input",
        type: "password",
        placeholder: scheme.type === "http" && scheme.scheme === "basic" ? "base64(user:pass)" : "Enter value",
        value: state.auth[name] || "",
      });
      input.addEventListener("input", function () {
        state.auth[name] = input.value;
        save("openapi-ui.auth", state.auth);
        if (onChange) onChange();
      });

      body.appendChild(h("label", { class: "modal__row" }, [
        h("span", { class: "modal__label" }, [name, h("span", { class: "modal__hint", text: hint })]),
        input,
      ]));
    });

    body.appendChild(h("p", { class: "modal__note", text: "Cookie based schemes are managed under Cookies in the toolbar. Browser cookies are sent automatically with credentials: include." }));
    modal("Auth", body);
  }

  function openRowPanel(options) {
    var body = h("div", { class: "modal__body" });
    var list = h("div", { class: "modal__list" });

    function commit() {
      save(options.storageKey, options.rows());
      if (options.onChange) options.onChange();
    }

    function addRow(row) {
      var nameInput = h("input", { class: "input", type: "text", placeholder: options.namePlaceholder, value: row.name || "" });
      var valueInput = h("input", { class: "input", type: "text", placeholder: "Value", value: row.value || "" });
      var toggle = h("input", { class: "modal__check", type: "checkbox" });
      toggle.checked = row.enabled !== false;

      nameInput.addEventListener("input", function () { row.name = nameInput.value; commit(); });
      valueInput.addEventListener("input", function () { row.value = valueInput.value; commit(); });
      toggle.addEventListener("change", function () { row.enabled = toggle.checked; commit(); });

      var remove = h("button", { class: "btn btn--sm", type: "button", text: "Remove" });
      remove.addEventListener("click", function () {
        options.remove(row);
        entryRow.remove();
        commit();
      });

      var entryRow = h("div", { class: "modal__header-row" }, [toggle, nameInput, valueInput, remove]);
      list.appendChild(entryRow);
    }

    options.rows().forEach(addRow);

    var add = h("button", { class: "btn btn--sm", type: "button", text: options.addLabel });
    add.addEventListener("click", function () {
      var row = { name: "", value: "", enabled: true };
      options.add(row);
      addRow(row);
      commit();
    });

    body.appendChild(list);
    body.appendChild(add);
    body.appendChild(h("p", { class: "modal__note", text: options.note }));
    modal(options.title, body);
  }

  function openHeaderPanel(onChange) {
    openRowPanel({
      title: "Custom headers",
      storageKey: "openapi-ui.headers",
      namePlaceholder: "Header name (e.g. X-Participant-Type)",
      addLabel: "+ Add header",
      note: "These headers are sent with every Try it out request and included in the cURL, Kotlin and TypeScript samples. Server defaults come from openapi.ui.headers.",
      rows: function () { return state.headers || []; },
      add: function (row) { state.headers.push(row); },
      remove: function (row) {
        state.headers = state.headers.filter(function (item) { return item !== row; });
      },
      onChange: onChange,
    });
  }

  function openCookiePanel(onChange) {
    openRowPanel({
      title: "Cookies",
      storageKey: "openapi-ui.cookies",
      namePlaceholder: "Cookie name (e.g. atn)",
      addLabel: "+ Add cookie",
      note: "On the same origin these are written to browser cookies before Try it out, so real requests carry them. On a different origin the browser cannot set them for you, so they only appear in the code samples. Server defaults come from openapi.ui.cookies.",
      rows: function () { return state.cookies || []; },
      add: function (row) { state.cookies.push(row); },
      remove: function (row) {
        state.cookies = state.cookies.filter(function (item) { return item !== row; });
      },
      onChange: onChange,
    });
  }

  var observer = null;

  function observeSections() {
    if (observer) observer.disconnect();
    var items = {};
    document.querySelectorAll(".nav__item").forEach(function (item) { items[item.dataset.target] = item; });

    observer = new IntersectionObserver(function (records) {
      records.forEach(function (record) {
        var item = items[record.target.id];
        if (!item) return;
        if (record.isIntersecting) {
          document.querySelectorAll(".nav__item.is-active").forEach(function (active) {
            active.classList.remove("is-active");
          });
          item.classList.add("is-active");
          item.scrollIntoView({ block: "nearest" });
          if (history.replaceState) history.replaceState(null, "", "#" + record.target.id);
        }
      });
    }, { rootMargin: "-15% 0px -70% 0px", threshold: 0 });

    document.querySelectorAll(".op").forEach(function (section) { observer.observe(section); });
  }

  /* ---------------- boot ---------------- */

  function boot() {
    fetch("config.json")
      .then(function (response) { return response.ok ? response.json() : {}; })
      .catch(function () { return {}; })
      .then(function (config) {
        Object.assign(state.config, config || {});
        return fetch(state.config.docsUrl);
      })
      .then(function (response) {
        if (!response.ok) throw new Error("Failed to load the spec (" + response.status + ")");
        return response.json();
      })
      .then(function (spec) {
        state.spec = spec;
        if (!state.headers) state.headers = defaultRows(state.config.headers);
        if (!state.cookies) {
          var rows = defaultRows(state.config.cookies);
          cookieSchemeNames().forEach(function (name) {
            if (!rows.some(function (row) { return row.name === name; })) {
              rows.push({ name: name, value: "", enabled: true });
            }
          });
          state.cookies = rows;
        }
        state.entries = collectEntries();
        document.title = (spec.info && spec.info.title) || "API Docs";
        renderSidebar();
        renderMain();
        if (location.hash) {
          var target = document.getElementById(location.hash.slice(1));
          if (target) target.scrollIntoView();
        }
      })
      .catch(function (error) {
        document.getElementById("main").innerHTML = '<div class="boot">' + escapeHtml(error.message) + "</div>";
      });
  }

  boot();
})();

browser.webRequest.onBeforeSendHeaders.addListener(
  (request) => {
    console.info("cors-unblock request:", request.url);
    for (let header of request.requestHeaders) {
      if (header.name.toLowerCase() === 'origin') {
        header.value = header.value || '*';
      }
    }
    return { requestHeaders: request.requestHeaders };
  },
  { urls: ['<all_urls>'] },
  ['blocking', 'requestHeaders']
);

browser.webRequest.onHeadersReceived.addListener(
  (response) => {
    console.info("cors-unblock response:", response.url);
    const headers = response.responseHeaders || [];

    const setHeader = (name, value) => {
      const existing = headers.find(h => h.name.toLowerCase() === name);
      if (existing) existing.value = value;
      else headers.push({ name, value });
    };

    setHeader('access-control-allow-origin', '*');
    setHeader('access-control-allow-methods', '*');
    setHeader('access-control-allow-headers', '*');
    setHeader('access-control-allow-credentials', 'true');

    return { responseHeaders: headers };
  },
  { urls: ['<all_urls>'] },
  ['blocking', 'responseHeaders']
);

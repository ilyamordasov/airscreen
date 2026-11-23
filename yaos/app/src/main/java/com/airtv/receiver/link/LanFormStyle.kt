package com.airtv.receiver.link

internal const val LAN_FORM_STYLE = """
    :root{color-scheme:dark;background:#0e101c;color:#f8f7fc;font:18px system-ui,sans-serif}
    body{box-sizing:border-box;max-width:36rem;min-height:100vh;margin:0 auto;padding:2rem 1rem;
      background:radial-gradient(ellipse at top right,#461d42,transparent 54%),
                 radial-gradient(ellipse at bottom left,#192b5f,transparent 54%)}
    h1{margin-top:0}p{color:#b3b2ca;line-height:1.5}
    form{padding:1.25rem;background:#191a2e;border:1px solid #434263;border-radius:1.25rem}
    label{display:block;font-weight:600}
    input,button{box-sizing:border-box;width:100%;padding:.85rem 1rem;margin:.5rem 0 1rem;
      border-radius:.75rem;font:inherit;color:inherit}
    input{background:#20213b;border:1px solid #656086}
    button{background:linear-gradient(110deg,#7732a8,#4946a8);border:1px solid #ac82df;font-weight:600;margin-bottom:0}
    :is(input,button):focus-visible{outline:2px solid #dc9cff;outline-offset:3px}
"""

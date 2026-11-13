#!/usr/bin/env python3
"""Build an unsigned Apple Shortcut; sign it with `shortcuts sign --mode anyone`.

The shortcut contains no pairing secret. Import questions ask the owner for
their TV's address and secret when installing it on iPhone.
"""

import plistlib
import sys


def token_string(attachment):
    return {
        "Value": {
            "string": "\uFFFC",
            "attachmentsByRange": {"{0, 1}": attachment},
        },
        "WFSerializationType": "WFTextTokenString",
    }


def form_field(name, value):
    return {
        "WFKey": {
            "Value": {"string": name, "attachmentsByRange": {}},
            "WFSerializationType": "WFTextTokenString",
        },
        "WFItemType": 0,
        "WFValue": token_string(value),
    }


TOKEN_UUID = "B7657613-254F-4382-8F14-03377E0B632A"
REQUEST_UUID = "0A648C52-B4D2-468C-BCEA-7DBAD15471E5"

workflow = {
    "WFWorkflowName": "На FreeCast TV",
    "WFWorkflowClientVersion": "4407.0.0",
    "WFWorkflowMinimumClientVersion": 900,
    "WFWorkflowMinimumClientVersionString": "900",
    "WFWorkflowIcon": {
        "WFWorkflowIconGlyphNumber": 61440,
        "WFWorkflowIconStartColor": 1440408063,
    },
    "WFWorkflowTypes": ["ActionExtension"],
    "WFWorkflowInputContentItemClasses": [
        "WFURLContentItem",
        "WFSafariWebPageContentItem",
    ],
    "WFWorkflowOutputContentItemClasses": [],
    "WFWorkflowHasOutputFallback": False,
    "WFWorkflowHasShortcutInputVariables": True,
    "WFWorkflowImportQuestions": [
        {
            "ActionIndex": 0,
            "Category": "Parameter",
            "ParameterKey": "WFTextActionText",
            "Text": "Введите ключ с экрана FreeCast TV (32 символа)",
            "DefaultValue": "",
        },
        {
            "ActionIndex": 1,
            "Category": "Parameter",
            "ParameterKey": "WFURL",
            "Text": "Введите адрес с экрана FreeCast TV целиком, включая /command",
            "DefaultValue": "http://192.168.1.2:38752/command",
        },
    ],
    "WFWorkflowActions": [
        {
            "WFWorkflowActionIdentifier": "is.workflow.actions.gettext",
            "WFWorkflowActionParameters": {
                "UUID": TOKEN_UUID,
                "WFTextActionText": "",
            },
        },
        {
            "WFWorkflowActionIdentifier": "is.workflow.actions.downloadurl",
            "WFWorkflowActionParameters": {
                "UUID": REQUEST_UUID,
                "Advanced": True,
                "WFURL": "http://192.168.1.2:38752/command",
                "WFHTTPMethod": "POST",
                "WFHTTPBodyType": "Form",
                "WFFormValues": {
                    "Value": {
                        "WFDictionaryFieldValueItems": [
                            form_field("url", {"Type": "ExtensionInput"}),
                            form_field(
                                "token",
                                {
                                    "Type": "ActionOutput",
                                    "OutputUUID": TOKEN_UUID,
                                    "OutputName": "Text",
                                },
                            ),
                        ]
                    },
                    "WFSerializationType": "WFDictionaryFieldValue",
                },
            },
        },
        {
            "WFWorkflowActionIdentifier": "is.workflow.actions.showresult",
            "WFWorkflowActionParameters": {
                "Text": token_string(
                    {
                        "Type": "ActionOutput",
                        "OutputUUID": REQUEST_UUID,
                        "OutputName": "Contents of URL",
                    }
                )
            },
        },
    ],
}

if len(sys.argv) != 2:
    raise SystemExit("usage: build_shortcut.py OUTPUT.shortcut")

with open(sys.argv[1], "wb") as output:
    plistlib.dump(workflow, output, fmt=plistlib.FMT_BINARY)

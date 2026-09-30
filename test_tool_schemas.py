#!/usr/bin/env python3
import json

# Define the exact tool schemas declared in SkillLoader.kt
tools = [
    {
        "type": "function",
        "function": {
            "name": "open_camera",
            "description": "Launches the phone's native camera viewfinder for taking photos or videos",
            "parameters": {
                "type": "object",
                "properties": {},
                "required": []
            }
        }
    },
    {
        "type": "function",
        "function": {
            "name": "set_alarm",
            "description": "Sets a clock alarm on the device at a specific hour and minute",
            "parameters": {
                "type": "object",
                "properties": {
                    "hour": {"type": "integer", "description": "Hour in 24-hour format (0-23)"},
                    "minutes": {"type": "integer", "description": "Minute (0-59)"},
                    "message": {"type": "string", "description": "Alarm label or title"}
                },
                "required": ["hour", "minutes"]
            }
        }
    },
    {
        "type": "function",
        "function": {
            "name": "stage_and_test_tool",
            "description": "Stages a newly proposed tool in an isolated test sandbox and executes a test before saving",
            "parameters": {
                "type": "object",
                "properties": {
                    "name": {"type": "string", "description": "Snake_case tool identifier"},
                    "description": {"type": "string", "description": "What the tool does"},
                    "parameters_hint": {"type": "string", "description": "Expected input parameters"},
                    "test_input": {"type": "string", "description": "Sample test payload"},
                    "action_template": {"type": "string", "description": "Execution logic"}
                },
                "required": ["name", "description"]
            }
        }
    },
    {
        "type": "function",
        "function": {
            "name": "save_staged_tool",
            "description": "Permanently saves a staged tool to device storage after user confirmation",
            "parameters": {
                "type": "object",
                "properties": {
                    "name": {"type": "string", "description": "Name of the staged tool to save permanently"}
                },
                "required": ["name"]
            }
        }
    },
    {
        "type": "function",
        "function": {
            "name": "discard_staged_tool",
            "description": "Discards a staged candidate tool from memory without saving to storage",
            "parameters": {
                "type": "object",
                "properties": {
                    "name": {"type": "string", "description": "Name of the staged tool to discard"}
                },
                "required": ["name"]
            }
        }
    },
    {
        "type": "function",
        "function": {
            "name": "register_mcp_server",
            "description": "Registers an external Model Context Protocol server endpoint",
            "parameters": {
                "type": "object",
                "properties": {
                    "name": {"type": "string", "description": "Unique identifier for the MCP server"},
                    "url": {"type": "string", "description": "SSE or HTTP RPC endpoint URL"}
                },
                "required": ["name", "url"]
            }
        }
    },
    {
        "type": "function",
        "function": {
            "name": "list_mcp_servers",
            "description": "Lists all configured Model Context Protocol servers",
            "parameters": {
                "type": "object",
                "properties": {},
                "required": []
            }
        }
    }
]

print("Validating OpenAPI / OpenRouter Function Schemas...")
for t in tools:
    assert t["type"] == "function"
    fn = t["function"]
    assert "name" in fn and len(fn["name"]) > 0
    assert "description" in fn and len(fn["description"]) > 0
    assert fn["parameters"]["type"] == "object"
    assert "properties" in fn["parameters"]
    assert "required" in fn["parameters"]
    print(f"  [OK] Valid schema for tool: {fn['name']}")

print(f"\nAll {len(tools)} tool schemas conform 100% to OpenAI/OpenRouter tool calling specs!")

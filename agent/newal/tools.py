"""Minimal adapter for NewAl's durable task_state module.

Only ToolError and the declarative registration decorator are needed by its
standalone checkpoint functions. Runtime tool registration belongs to Term Agent.
"""
class ToolError(RuntimeError):
    pass

def tool(*arguments, **options):
    return lambda fn: fn

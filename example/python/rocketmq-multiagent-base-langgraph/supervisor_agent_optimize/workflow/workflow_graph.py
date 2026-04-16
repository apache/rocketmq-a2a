"""LangGraph workflow graph builder"""
from langgraph.graph import StateGraph, END
from supervisor_agent_optimize.my_common.models.models import AgentState
from supervisor_agent_optimize.workflow.workflow_nodes import router_node, weather_node, travel_node, chat_node, \
    route_after_router, route_after_weather


def build_workflow():
    """Build and compile the LangGraph workflow"""
    workflow = StateGraph(AgentState)

    workflow.add_node("router", router_node)
    workflow.add_node("weather_node", weather_node)
    workflow.add_node("travel_node", travel_node)
    workflow.add_node("chat_node", chat_node)

    workflow.set_entry_point("router")

    workflow.add_conditional_edges(
        "router",
        route_after_router,
        {
            "weather_node": "weather_node",
            "travel_node": "weather_node",
            "chat_node": "chat_node",
        }
    )

    workflow.add_conditional_edges(
        "weather_node",
        route_after_weather,
        {
            "travel_node": "travel_node",
            END: END
        }
    )

    workflow.add_edge("travel_node", END)
    workflow.add_edge("chat_node", END)

    return workflow.compile()
